package com.deepseek.plugin.completion

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import javax.swing.Icon

/**
 * 基于静态分析与类型推导的基础补全 Provider
 *
 * 【原理】
 * 1. AST 构建 — 通过 IntelliJ PSI 实时解析源代码，理解代码结构、作用域及依赖关系
 * 2. 语义索引 — 通过全局索引检索项目内所有类/方法/字段，结合当前上下文过滤不可访问项
 * 3. 规则匹配 — 利用预定义的语言语法规则和实时模板进行确定性匹配，确保补全项符合语法规范
 *
 * 【行为】
 * 作为 AI 补全的前置过滤：
 * - 静态分析能确定合法候选时 → 添加候选并阻止 AI 请求（节省 token）
 * - 静态分析候选不足时 → 允许 DeepSeekCompletionProvider 触发 AI API
 *
 * 通过 [satisfies] 方法对外暴露判断结果，供 [DeepSeekCompletionProvider] 查询。
 */
class StaticAnalysisCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private val LOG = Logger.getInstance(StaticAnalysisCompletionProvider::class.java)

        const val MIN_CANDIDATES_THRESHOLD = 2
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.9f
    }

    private val annotationAware = AnnotationAwareCompletion()
    private val commentAware = CommentAwareCompletion()

    private val isAnnotationAwareEnabled: Boolean
        get() = DeepSeekSettings.instance.annotationAwareEnabled

    private val isCommentAwareEnabled: Boolean
        get() = DeepSeekSettings.instance.commentAwareEnabled

    /**
     * 单次分析结果的数据载体。
     */
    data class AnalysisResult(
        val candidates: List<CompletionCandidate>,
        val contextType: CompletionContext
    ) {
        /** 静态分析是否已充分满足补全需求 */
        val isSatisfied: Boolean
            get() = when {
                candidates.isEmpty() -> false
                candidates.any { it.relevanceScore >= HIGH_CONFIDENCE_THRESHOLD } -> true
                candidates.size >= MIN_CANDIDATES_THRESHOLD -> true
                else -> false
            }
    }

    data class CompletionCandidate(
        val displayName: String,
        val insertText: String,
        val typeText: String?,
        val icon: Icon?,
        val description: String? = null,
        /** 匹配置信度 0.0~1.0 */
        val relevanceScore: Float = 0.5f
    )

    enum class CompletionContext {
        /** 成员访问：expr.xxx */
        MEMBER_ACCESS,
        /** 类型引用：new Xxx, extends Xxx, 泛型参数 */
        TYPE_REFERENCE,
        /** 变量/表达式位置 */
        EXPRESSION,
        /** 关键字位置 */
        KEYWORD,
        /** 注解装饰位置：@xxx */
        ANNOTATION_DECORATOR,
        /** 无法识别 */
        UNKNOWN
    }

    // ==================== 入口 ====================

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: return
        val file = parameters.originalFile ?: return
        if (file !is PsiJavaFile) return // 仅 Java 文件（扩展点：后续支持 Kotlin）
        val offset = parameters.offset

        try {
            val analysis = analyze(project, file, offset)
            for (c in analysis.candidates) {
                result.addElement(toLookupElement(c))
            }
            if (analysis.candidates.isNotEmpty()) {
                LOG.debug("StaticAnalysis: ${analysis.candidates.size} candidates, context=${analysis.contextType}, satisfied=${analysis.isSatisfied}")
            }
        } catch (e: Exception) {
            LOG.warn("StaticAnalysis completion error", e)
        }
    }

    /**
     * 暴露给外部调用的纯分析接口（无副作用），供 AI Provider 判断是否需要 fallback。
     */
    fun analyze(project: Project, file: PsiFile, offset: Int): AnalysisResult {
        val element = file.findElementAt(offset) ?: return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)

        // 注解感知：检测注解上下文
        if (isAnnotationAwareEnabled && annotationAware.isAnnotationContext(element, offset)) {
            val annotationResult = analyzeAnnotationDecorator(project, element)
            if (annotationResult.candidates.isNotEmpty()) {
                return annotationResult
            }
        }

        // 注释感知：检测光标前的注释内容
        if (isCommentAwareEnabled) {
            val commentResult = analyzeCommentContext(element, offset)
            if (commentResult.candidates.isNotEmpty()) {
                return commentResult
            }
        }

        return when (val ctx = determineContext(element, file, offset)) {
            CompletionContext.MEMBER_ACCESS  -> {
                val result = analyzeMemberAccess(project, element)
                if (isAnnotationAwareEnabled) {
                    result.withSyntheticMembers(element)
                } else result
            }
            CompletionContext.TYPE_REFERENCE -> analyzeTypeReference(project, element, file)
            CompletionContext.EXPRESSION     -> {
                val result = analyzeExpression(project, element, offset)
                if (isAnnotationAwareEnabled) {
                    result.withContextSuggestions(element)
                } else result
            }
            CompletionContext.KEYWORD        -> analyzeKeyword(element)
            CompletionContext.ANNOTATION_DECORATOR -> analyzeAnnotationDecorator(project, element)
            CompletionContext.UNKNOWN        -> analyzeFallback(project, element, file, offset)
        }
    }

    // ==================== 上下文检测 ====================

    private fun determineContext(element: PsiElement, file: PsiFile, offset: Int): CompletionContext {
        // 1) 最优先：成员访问（点号右侧）
        //    findElementAt(offset) 可能返回点号本身或标识符，
        //    所以检查 parent 或 prevSibling 是否为点号
        val parent = element.parent
        if (parent is PsiReferenceExpression) {
            val qualifier = parent.qualifierExpression
            if (qualifier != null && qualifier.textOffset < offset) {
                return CompletionContext.MEMBER_ACCESS
            }
        }
        // 光标紧跟在点号后面但 findElementAt 返回的是点号自身
        if (element.text == "." && element.parent is PsiReferenceExpression) {
            return CompletionContext.MEMBER_ACCESS
        }

        // 2) 类型引用：new 关键字之后
        if (element.text == "new" ||
            element.prevSibling?.text == "new" ||
            parent?.text == "new") {
            return CompletionContext.TYPE_REFERENCE
        }

        // 3) 逐级上溯检查
        var walk: PsiElement = element
        while (walk !is PsiFile) {
            when {
                walk.parent is PsiTypeElement -> return CompletionContext.TYPE_REFERENCE
                walk is PsiJavaCodeReferenceElement && walk.parent !is PsiReferenceExpression ->
                    return CompletionContext.TYPE_REFERENCE
                walk is PsiExpression -> return CompletionContext.EXPRESSION
            }
            walk = walk.parent ?: break
        }

        return CompletionContext.UNKNOWN
    }

    // ==================== 注解装饰器补全 ====================

    /**
     * 在 @ 符号位置推荐适用的注解名。
     * 根据上下文（类/方法/字段/参数）过滤可用的注解。
     */
    private fun analyzeAnnotationDecorator(project: Project, element: PsiElement): AnalysisResult {
        val applicable = annotationAware.getApplicableAnnotations(element)
        if (applicable.isEmpty()) return AnalysisResult(emptyList(), CompletionContext.ANNOTATION_DECORATOR)

        val candidates = applicable.map { anno ->
            CompletionCandidate(
                displayName = anno,
                insertText = anno.substring(1), // 去掉 @ 前缀，因为已经在编辑器中
                typeText = "annotation",
                icon = AllIcons.Nodes.Annotationtype,
                description = "Annotation",
                relevanceScore = 0.9f
            )
        }
        return AnalysisResult(candidates, CompletionContext.ANNOTATION_DECORATOR)
    }

    // ==================== 注释感知补全 ====================

    /**
     * 分析光标前的注释内容，返回匹配的代码补全建议。
     * 支持：getter/setter、构造器、设计模式、CRUD、Spring 模式等。
     */
    private fun analyzeCommentContext(element: PsiElement, offset: Int): AnalysisResult {
        if (!commentAware.isAfterComment(element, offset)) {
            return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)
        }
        val suggestions = commentAware.analyzeComments(element, offset)
        if (suggestions.isEmpty()) return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)

        val candidates = commentAware.toCompletionCandidates(suggestions)
        LOG.debug("CommentAware: ${candidates.size} suggestions from comment")
        return AnalysisResult(candidates, CompletionContext.UNKNOWN)
    }

    // ==================== 成员访问补全 ====================

    private fun analyzeMemberAccess(project: Project, element: PsiElement): AnalysisResult {
        val ref = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java)
            ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)

        val qualifier = ref.qualifierExpression ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)
        val qualifierType = qualifier.type ?: return AnalysisResult(emptyList(), CompletionContext.MEMBER_ACCESS)

        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()

        // 遍历类型层级（类 + 父类 + 接口）
        forEachTypeInHierarchy(qualifierType, project) { psiClass ->
            val isStaticCtx = (qualifier is PsiKeyword && qualifier.text == "super") ||
                (qualifier is PsiJavaCodeReferenceElement && qualifier.resolve() is PsiClass)

            for (m in psiClass.methods) {
                if (isStaticCtx && !m.hasModifierProperty(PsiModifier.STATIC)) continue
                if (m.isConstructor || m.name in seen) continue
                if (!isAccessible(m, currentPackage(element))) continue
                seen.add(m.name)

                val params = m.parameterList.parameters.joinToString(", ") {
                    it.type?.presentableText ?: "?"
                }
                candidates.add(CompletionCandidate(
                    displayName = m.name,
                    insertText = if (m.parameterList.parametersCount == 0) "${m.name}()" else "${m.name}(${params})",
                    typeText = m.returnType?.presentableText,
                    icon = AllIcons.Nodes.Method,
                    description = "($params)",
                    relevanceScore = 0.95f
                ))
            }

            for (f in psiClass.fields) {
                if (isStaticCtx && !f.hasModifierProperty(PsiModifier.STATIC)) continue
                if (f.name in seen) continue
                if (!isAccessible(f, currentPackage(element))) continue
                seen.add(f.name)

                candidates.add(CompletionCandidate(
                    displayName = f.name,
                    insertText = f.name,
                    typeText = f.type?.presentableText,
                    icon = AllIcons.Nodes.Field,
                    relevanceScore = 0.85f
                ))
            }
        }

        // 如果限定符是 this/super 还加 instance 方法
        return AnalysisResult(candidates, CompletionContext.MEMBER_ACCESS)
    }

    /** 为成员访问结果添加注解生成的合成成员（如 Lombok @Data 生成的 getter/setter），返回新结果 */
    private fun AnalysisResult.withSyntheticMembers(element: PsiElement): AnalysisResult {
        val ref = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java) ?: return this
        val qualifier = ref.qualifierExpression ?: return this
        val qualifierType = qualifier.type ?: return this

        // 静态调用（ClassName.method）不加合成成员
        if (qualifier is PsiJavaCodeReferenceElement && qualifier.resolve() is PsiClass) return this

        val cls = (qualifierType as? PsiClassType)?.resolve() ?: return this
        if (cls.annotations.isEmpty()) return this

        val synthetic = annotationAware.getSyntheticMembers(cls)
        val seenNames = candidates.map { it.displayName }.toSet()

        val extra = mutableListOf<CompletionCandidate>()
        for (sm in synthetic) {
            if (sm.name in seenNames) continue
            extra.add(CompletionCandidate(
                displayName = sm.name,
                insertText = if (sm.parameterTypes.isEmpty()) "${sm.name}()" else "${sm.name}(${sm.parameterTypes.first()})",
                typeText = sm.returnType,
                icon = when (sm.kind) {
                    AnnotationAwareCompletion.MemberKind.GETTER -> AllIcons.Nodes.PropertyRead
                    AnnotationAwareCompletion.MemberKind.SETTER -> AllIcons.Nodes.PropertyWrite
                    AnnotationAwareCompletion.MemberKind.LOGGER -> AllIcons.Nodes.Variable
                    else -> AllIcons.Nodes.Method
                },
                description = sm.description,
                relevanceScore = 0.9f
            ))
        }
        return if (extra.isEmpty()) this
        else AnalysisResult(candidates + extra, contextType)
    }

    private fun analyzeTypeReference(project: Project, element: PsiElement, file: PsiFile): AnalysisResult {
        val psiFile = file as? PsiJavaFile
        val currentPkg = psiFile?.packageName ?: ""
        val imported = getImportedShortNames(psiFile)

        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()
        val cache = PsiShortNamesCache.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)

        // 1) 同一包内的类
        if (currentPkg.isNotEmpty()) {
            val pkg = JavaPsiFacade.getInstance(project).findPackage(currentPkg)
            if (pkg != null) {
                for (cls in pkg.getClasses(allScope)) {
                    val name = cls.name ?: continue
                    if (name !in seen) {
                        seen.add(name)
                        candidates.add(CompletionCandidate(
                            displayName = name,
                            insertText = name,
                            typeText = cls.qualifiedName,
                            icon = AllIcons.Nodes.Class,
                            relevanceScore = 0.95f
                        ))
                    }
                }
            }
        }

        // 2) 显式导入的类
        for (shortName in imported) {
            if (shortName in seen) continue
            for (cls in cache.getClassesByName(shortName, allScope)) {
                val name = cls.name ?: continue
                if (name !in seen) {
                    seen.add(name)
                    candidates.add(CompletionCandidate(
                        displayName = name,
                        insertText = name,
                        typeText = cls.qualifiedName,
                        icon = AllIcons.Nodes.Class,
                        relevanceScore = 0.85f
                    ))
                }
            }
        }

        // 3) java.lang 中常用类（隐式导入）
        val javaLang = listOf(
            "String", "Integer", "Long", "Double", "Float", "Boolean",
            "Object", "Exception", "RuntimeException", "System",
            "Math", "StringBuilder", "StringBuffer", "Thread",
            "Comparable", "Iterable", "Throwable"
        )
        for (name in javaLang) {
            if (name !in seen) {
                seen.add(name)
                candidates.add(CompletionCandidate(
                    displayName = name,
                    insertText = name,
                    typeText = "java.lang.$name",
                    icon = AllIcons.Nodes.Class,
                    relevanceScore = 0.6f
                ))
            }
        }

        return AnalysisResult(candidates, CompletionContext.TYPE_REFERENCE)
    }

    // ==================== 表达式补全 ====================

    private fun analyzeExpression(project: Project, element: PsiElement, offset: Int = element.textOffset): AnalysisResult {
        val candidates = mutableListOf<CompletionCandidate>()
        val seen = mutableSetOf<String>()

        // 1) 局部变量 + 方法参数
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null) {
            for (p in method.parameterList.parameters) {
                val pName = p.name ?: continue
                if (pName !in seen) {
                    seen.add(pName)
                    candidates.add(CompletionCandidate(
                        displayName = pName,
                        insertText = pName,
                        typeText = p.type?.presentableText,
                        icon = AllIcons.Nodes.Parameter,
                        relevanceScore = 1.0f
                    ))
                }
            }

            val body = method.body ?: return AnalysisResult(candidates, CompletionContext.EXPRESSION)
            for (v in PsiTreeUtil.collectElementsOfType(body, PsiLocalVariable::class.java)) {
                val vName = v.name ?: continue
                if (vName !in seen && v.textOffset < offset) {
                    seen.add(vName)
                    candidates.add(CompletionCandidate(
                        displayName = vName,
                        insertText = vName,
                        typeText = v.type?.presentableText,
                        icon = AllIcons.Nodes.Variable,
                        relevanceScore = 1.0f
                    ))
                }
            }
        }

        // 2) 当前类字段
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (psiClass != null) {
            for (f in psiClass.fields) {
                val fName = f.name ?: continue
                if (fName !in seen) {
                    seen.add(fName)
                    candidates.add(CompletionCandidate(
                        displayName = fName,
                        insertText = fName,
                        typeText = f.type?.presentableText,
                        icon = AllIcons.Nodes.Field,
                        relevanceScore = 0.8f
                    ))
                }
            }
        }

        // 3) this / super / 常量
        if (psiClass != null) {
            candidates.add(CompletionCandidate("this", "this", psiClass.qualifiedName, AllIcons.Nodes.Variable, relevanceScore = 0.9f))
            if (psiClass.superClass != null) {
                candidates.add(CompletionCandidate("super", "super", psiClass.superClass?.qualifiedName, AllIcons.Nodes.Variable, relevanceScore = 0.8f))
            }
        }
        candidates.add(CompletionCandidate("true", "true", "boolean", AllIcons.Nodes.Variable, relevanceScore = 0.5f))
        candidates.add(CompletionCandidate("false", "false", "boolean", AllIcons.Nodes.Variable, relevanceScore = 0.5f))
        candidates.add(CompletionCandidate("null", "null", null, AllIcons.Nodes.Variable, relevanceScore = 0.5f))

        return AnalysisResult(candidates, CompletionContext.EXPRESSION)
    }

    /** 为表达式结果添加注解驱动的代码模式建议，返回新结果 */
    private fun AnalysisResult.withContextSuggestions(element: PsiElement): AnalysisResult {
        val ctx = annotationAware.analyzeAnnotations(element)
        if (ctx.hints.isEmpty()) return this
        val suggestions = annotationAware.getContextSuggestions(element, ctx)
        if (suggestions.isEmpty()) return this
        return AnalysisResult(candidates + suggestions, contextType)
    }

    // ==================== 关键字补全 ====================

    private fun analyzeKeyword(element: PsiElement): AnalysisResult {
        val snippets = mapOf(
            "if"     to "if () {\n}",
            "else"   to "else {\n}",
            "for"    to "for () {\n}",
            "while"  to "while () {\n}",
            "do"     to "do {\n} while ();",
            "try"    to "try {\n} catch () {\n}",
            "catch"  to "catch () {\n}",
            "finally" to "finally {\n}",
            "switch" to "switch () {\n\tcase :\n\t\tbreak;\n}",
            "case"   to "case ",
            "return" to "return ",
            "throw"  to "throw ",
            "new"    to "new ",
            "break"  to "break;",
            "continue" to "continue;",
            "synchronized" to "synchronized () {\n}"
        )
        val candidates = snippets.map { (kw, snippet) ->
            CompletionCandidate(kw, snippet, "keyword", AllIcons.Nodes.Variable, relevanceScore = 0.4f)
        }
        return AnalysisResult(candidates, CompletionContext.KEYWORD)
    }

    // ==================== 兜底 ====================

    private fun analyzeFallback(project: Project, element: PsiElement, file: PsiFile, offset: Int): AnalysisResult {
        // 先检查注解上下文
        if (isAnnotationAwareEnabled) {
            val annoResult = analyzeAnnotationDecorator(project, element)
            if (annoResult.candidates.isNotEmpty()) return annoResult
        }
        // 依次尝试各策略
        listOf(
            { analyzeMemberAccess(project, element) },
            { analyzeTypeReference(project, element, file) },
            { analyzeExpression(project, element, offset) },
            { analyzeKeyword(element) }
        ).forEach { strategy ->
            val r = strategy()
            if (r.candidates.isNotEmpty()) return r
        }
        return AnalysisResult(emptyList(), CompletionContext.UNKNOWN)
    }

    // ==================== 工具方法 ====================

    /** 遍历类型层级（类本身 + 父类 + 接口），最多深入 10 层防止 StackOverflow */
    private fun forEachTypeInHierarchy(type: PsiType, project: Project, action: (PsiClass) -> Unit) {
        val seen = mutableSetOf<String>()

        fun walk(psiClass: PsiClass?, depth: Int = 0) {
            if (psiClass == null) return
            if (depth > 10) return // 深度限制，防止循环继承
            val qName = psiClass.qualifiedName ?: return
            if (qName in seen) return
            seen.add(qName)

            // Object 的成员对补全无意义
            if (qName == "java.lang.Object") return

            action(psiClass)
            walk(psiClass.superClass, depth + 1)
            psiClass.interfaces.forEach { walk(it, depth + 1) }
        }

        val cls = (type as? PsiClassType)?.resolve()
        walk(cls)
    }

    private fun currentPackage(element: PsiElement): String? {
        return (element.containingFile as? PsiJavaFile)?.packageName
    }

    private fun isAccessible(member: PsiModifierListOwner, currentPackage: String?): Boolean {
        if (member.hasModifierProperty(PsiModifier.PUBLIC)) return true
        if (member.hasModifierProperty(PsiModifier.PROTECTED)) return true
        if (member.hasModifierProperty(PsiModifier.PRIVATE)) return false
        // package-private
        val memberFile = member.containingFile as? PsiJavaFile ?: return false
        return memberFile.packageName == currentPackage
    }

    private fun getImportedShortNames(psiFile: PsiJavaFile?): Set<String> {
        if (psiFile == null) return emptySet()
        val imports = mutableSetOf<String>()
        for (stmt in psiFile.importList?.allImportStatements ?: emptyArray()) {
            val ref = stmt.importReference ?: continue
            val qn = ref.qualifiedName ?: continue
            if (stmt.isOnDemand) {
                // star import — 不展开，由全局搜索兜底
                continue
            }
            imports.add(qn.substringAfterLast('.'))
        }
        return imports
    }

    private fun toLookupElement(c: CompletionCandidate): LookupElementBuilder {
        val builder = LookupElementBuilder.create(c.insertText)
            .withPresentableText(c.displayName)
            .withIcon(c.icon ?: AllIcons.Nodes.Variable)
        var b = builder
        if (c.typeText != null) b = b.withTypeText(c.typeText)
        if (c.description != null) b = b.withTailText(c.description)
        return b
    }
}

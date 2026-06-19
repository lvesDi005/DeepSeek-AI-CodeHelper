package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * 注释感知补全引擎。
 *
 * 分析用户写在光标前的注释内容，理解代码意图，自动推荐匹配的代码模板。
 *
 * 【工作流程】
 * 1. 检测光标前是否有注释行（// 或 /星 或 /星星）
 * 2. 提取注释文本中的关键词和意图
 * 3. 匹配预定义的代码模式模板
 * 4. 返回匹配的代码补全候选
 *
 * 【支持场景】
 * - 注释后跟空行，光标在空行上 → 生成注释描述的代码
 * - 注释行末尾直接补全 → 在注释后追加代码
 * - 类/方法/字段前的文档注释 → 根据上下文理解意图
 */
class CommentAwareCompletion {

    companion object {
        private val LOG = Logger.getInstance(CommentAwareCompletion::class.java)

        // ==================== 模式常量 ====================

        // ---- Getter / Setter ----
        private val PATTERN_GETTER     = Regex(
            "(?:getter|get|取得|获取|得到).*?(?:for|变量|字段|属性)?\\s*(\\S+)" +
            "|(\\S+).*?(?:的)?(?:getter|get|取得|获取|得到)" +
            "|获取.*?(\\S+).*?(?:字段|属性|变量)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_SETTER     = Regex(
            "(?:setter|set|设置|写入|设定).*?(?:for|变量|字段|属性)?\\s*(\\S+)" +
            "|(\\S+).*?(?:的)?(?:setter|set|设置|写入)" +
            "|设置.*?(\\S+).*?(?:字段|属性|变量)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_GETSET     = Regex(
            "(?:getter\\s*(?:and|&)?\\s*setter|get/set|读写|getter和setter)" +
            "|同时.*?(?:获取|读取|取).*?(?:设置|写入|写)" +
            "|全字段.*?(?:getter|setter|读写)",
            RegexOption.IGNORE_CASE
        )

        // ---- Constructor ----
        private val PATTERN_ALL_ARGS_CTOR = Regex("(all.arg|全参|全部参数|完整构造)", RegexOption.IGNORE_CASE)
        private val PATTERN_NO_ARGS_CTOR  = Regex("(no.arg|无参|空构造|默认构造)", RegexOption.IGNORE_CASE)
        private val PATTERN_CTOR          = Regex("(constructor|构造|构造器|构造函数|全参构造器|无参构造器)", RegexOption.IGNORE_CASE)

        // ---- Design Patterns ----
        private val PATTERN_SINGLETON   = Regex("(singleton|单例|单例模式)", RegexOption.IGNORE_CASE)
        private val PATTERN_BUILDER     = Regex("(builder|建造者|构建器|Builder模式)", RegexOption.IGNORE_CASE)
        private val PATTERN_FACTORY     = Regex("(factory|工厂|工厂模式|工厂方法)", RegexOption.IGNORE_CASE)
        private val PATTERN_STRATEGY    = Regex("(strategy|策略|策略模式)", RegexOption.IGNORE_CASE)
        private val PATTERN_OBSERVER    = Regex("(observer|观察者|监听|订阅)", RegexOption.IGNORE_CASE)
        private val PATTERN_PROXY       = Regex("(proxy|代理|代理模式)", RegexOption.IGNORE_CASE)
        private val PATTERN_TEMPLATE    = Regex("(template|模板|模板方法)", RegexOption.IGNORE_CASE)

        // ---- CRUD ----
        private val PATTERN_FIND_BY_ID  = Regex(
            "(?:find|查找|查询|搜索|获取).*?(?:by|by\\s+id|根据|通过)" +
            "|(?:根据|通过).*?(?:id|主键).*?(?:find|查找|查询|搜索|根据|获取)" +
            "|按.*id.*(?:查找|查询|搜索|获取)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_FIND_ALL    = Regex(
            "(?:findAll|find.all|查询所有|全部|list.all|获取全部|批量查询|所有记录|全部数据|所有数据)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_SAVE        = Regex(
            "(?:save|保存|新增|创建|insert|添加|写入|新建)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_UPDATE      = Regex(
            "(?:update|更新|修改|edit|编辑|变更)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_DELETE      = Regex(
            "(?:delete|删除|移除|remove).*?(?:by|by\\s+id|根据|通过)?" +
            "|(?:根据|通过).*?(?:id|主键).*?(?:delete|删除|移除)" +
            "|按.*id.*(?:删除|移除)",
            RegexOption.IGNORE_CASE
        )
        private val PATTERN_PAGE        = Regex(
            "(?:page|分页|翻页|pagination|pageable|分页查询|列表分页)",
            RegexOption.IGNORE_CASE
        )

        // ---- Common Infrastructure ----
        private val PATTERN_LOGGER      = Regex("(logger|log|日志|logging|记录日志|打日志|打印日志)", RegexOption.IGNORE_CASE)
        private val PATTERN_AUTOWIRED   = Regex("(autowired|inject|注入|依赖注入|装配|自动注入)", RegexOption.IGNORE_CASE)
        private val PATTERN_MAPPER      = Regex("(mapper|map|convert|转换|转化|映射|entity.*dto|dto.*entity|对象转换|类型转换|转化器)", RegexOption.IGNORE_CASE)
        private val PATTERN_VALIDATE    = Regex("(validate|校验|验证|检查|validation|参数校验|数据校验)", RegexOption.IGNORE_CASE)
        private val PATTERN_CONFIG      = Regex("(config|配置|configuration|@Bean|bean|注册)", RegexOption.IGNORE_CASE)
        private val PATTERN_CACHE       = Regex("(cache|缓存|cacheable|@Cacheable)", RegexOption.IGNORE_CASE)
        private val PATTERN_ASYNC       = Regex("(async|异步|@Async|并行)", RegexOption.IGNORE_CASE)
        private val PATTERN_SCHEDULE    = Regex("(schedule|定时|调度|cron|@Scheduled|任务)", RegexOption.IGNORE_CASE)
        private val PATTERN_THREAD_SAFE = Regex("(thread.safe|线程安全|synchronized|并发安全|lock)", RegexOption.IGNORE_CASE)
        private val PATTERN_UTILITY     = Regex("(util|utility|helper|工具|辅助|静态方法)", RegexOption.IGNORE_CASE)
        private val PATTERN_DTO         = Regex("(dto|vo|数据传输|视图对象|data.transfer)", RegexOption.IGNORE_CASE)
        private val PATTERN_REST_API    = Regex("(rest|api|endpoint|接口|REST|web|controller)", RegexOption.IGNORE_CASE)
        private val PATTERN_EXCEPTION   = Regex("(exception|异常|错误|error|自定义异常)", RegexOption.IGNORE_CASE)
        private val PATTERN_ENUM        = Regex("(enum|枚举|常量|枚举类)", RegexOption.IGNORE_CASE)
        private val PATTERN_TEST        = Regex("(test|测试|unit.test|单元测试|@Test)", RegexOption.IGNORE_CASE)
        private val PATTERN_STREAM      = Regex("(stream|流|lambda|函数式|filter|map|collect)", RegexOption.IGNORE_CASE)
    }

    // ==================== 模式定义 ====================

    /** 注释模式：匹配词 → 代码模板生成函数 */
    private val patterns: List<CommentPattern> = listOf(
        // ---- Logger ----
        CommentPattern(PATTERN_LOGGER) { match, ctx ->
            val loggerType = if (match.value.contains("log4j", ignoreCase = true)) "org.apache.log4j.Logger" else "org.slf4j.Logger"
            val factoryMethod = if (match.value.contains("log4j", ignoreCase = true)) "Logger.getLogger" else "LoggerFactory.getLogger"
            listOf(CodeSuggestion(
                name = "Logger",
                text = "private static final $loggerType log = $factoryMethod(${ctx.className}.class);",
                type = "logging",
                description = "SLF4J Logger"
            ))
        },

        // ---- Getter ----
        CommentPattern(PATTERN_GETTER) { match, ctx ->
            // 字段名可能在 groups[1]、groups[2] 或 groups[3] 中，取决于匹配到的分支
            val fieldName = match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: return@CommentPattern emptyList()
            val fieldType = ctx.findFieldType(fieldName) ?: "String"
            listOf(CodeSuggestion(
                name = "get$fieldName",
                text = "public $fieldType get${fieldName.replaceFirstChar { it.uppercase() }}() {\n    return $fieldName;\n}",
                type = "getter",
                description = "Getter for $fieldName"
            ))
        },

        // ---- Setter ----
        CommentPattern(PATTERN_SETTER) { match, ctx ->
            // 字段名可能在 groups[1]、groups[2] 或 groups[3] 中
            val fieldName = match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: return@CommentPattern emptyList()
            val fieldType = ctx.findFieldType(fieldName) ?: "String"
            listOf(CodeSuggestion(
                name = "set$fieldName",
                text = "public void set${fieldName.replaceFirstChar { it.uppercase() }}($fieldType $fieldName) {\n    this.$fieldName = $fieldName;\n}",
                type = "setter",
                description = "Setter for $fieldName"
            ))
        },

        // ---- Getter + Setter ----
        CommentPattern(PATTERN_GETSET) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            fields.flatMap { f ->
                val name = f.name ?: return@flatMap emptyList()
                val type = f.type?.presentableText ?: "String"
                val capName = name.replaceFirstChar { it.uppercase() }
                listOf(
                    CodeSuggestion(name = "get$capName", text = "public $type get$capName() {\n    return $name;\n}", type = "getter"),
                    CodeSuggestion(name = "set$capName", text = "public void set$capName($type $name) {\n    this.$name = $name;\n}", type = "setter")
                )
            }
        },

        // ---- All-args Constructor ----
        CommentPattern(PATTERN_ALL_ARGS_CTOR) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            if (fields.isEmpty()) return@CommentPattern emptyList()
            val params = fields.joinToString(", ") { "${it.type?.presentableText ?: "Object"} ${it.name}" }
            val assignments = fields.joinToString("\n    ") { "this.${it.name} = ${it.name};" }
            listOf(CodeSuggestion(
                name = "AllArgsConstructor",
                text = "public ${ctx.className}($params) {\n    $assignments\n}",
                type = "constructor",
                description = "All-args constructor"
            ))
        },

        // ---- No-args Constructor ----
        CommentPattern(PATTERN_NO_ARGS_CTOR) { _, ctx ->
            listOf(CodeSuggestion(
                name = "NoArgsConstructor",
                text = "public ${ctx.className}() {\n    \n}",
                type = "constructor",
                description = "No-arg constructor"
            ))
        },

        // ---- Constructor (generic) ----
        CommentPattern(PATTERN_CTOR) { _, ctx ->
            if (ctx.psiClass == null) return@CommentPattern emptyList()
            val fields = ctx.psiClass!!.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            if (fields.isEmpty()) return@CommentPattern emptyList()
            val params = fields.joinToString(", ") { "${it.type?.presentableText ?: "Object"} ${it.name}" }
            val assignments = fields.joinToString("\n    ") { "this.${it.name} = ${it.name};" }
            listOf(CodeSuggestion(
                name = "Constructor",
                text = "public ${ctx.className}($params) {\n    $assignments\n}",
                type = "constructor",
                description = "Constructor with all fields"
            ))
        },

        // ---- Singleton ----
        CommentPattern(PATTERN_SINGLETON) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Singleton",
                text = "private static ${ctx.className} instance;\n\nprivate ${ctx.className}() {}\n\npublic static ${ctx.className} getInstance() {\n    if (instance == null) {\n        instance = new ${ctx.className}();\n    }\n    return instance;\n}",
                type = "design-pattern",
                description = "Singleton pattern (lazy)"
            ), CodeSuggestion(
                name = "Singleton (enum)",
                text = "enum ${ctx.className} {\n    INSTANCE;\n    \n}",
                type = "design-pattern",
                description = "Singleton pattern (enum)"
            ))
        },

        // ---- Builder ----
        CommentPattern(PATTERN_BUILDER) { _, ctx ->
            val innerBuilder = """
public static class Builder {
    ${(ctx.psiClass?.fields?.filter { !it.hasModifierProperty(PsiModifier.STATIC) }?.joinToString("\n    ") { "private ${it.type?.presentableText ?: "Object"} ${it.name};" } ?: "")}

    public Builder() {}

    ${(ctx.psiClass?.fields?.filter { !it.hasModifierProperty(PsiModifier.STATIC) }?.joinToString("\n    ") { f ->
        val name = f.name ?: return@joinToString ""
        val type = f.type?.presentableText ?: "Object"
        "public Builder $name($type $name) {\n        this.$name = $name;\n        return this;\n    }"
    } ?: "")}

    public ${ctx.className} build() {
        return new ${ctx.className}(this);
    }
}
""".trimIndent()
            listOf(CodeSuggestion(
                name = "Builder",
                text = innerBuilder,
                type = "design-pattern",
                description = "Builder pattern (inner class)"
            ))
        },

        // ---- Factory ----
        CommentPattern(PATTERN_FACTORY) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Factory Method",
                text = "public static ${ctx.className} create() {\n    return new ${ctx.className}();\n}",
                type = "design-pattern",
                description = "Simple factory method"
            ))
        },

        // ---- CRUD: find by id ----
        CommentPattern(PATTERN_FIND_BY_ID) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
                .removeSuffix("Controller").removeSuffix("Impl")
            listOf(CodeSuggestion(
                name = "findById",
                text = "public $entityName findById(Long id) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findById(id)\n        .orElseThrow(() -> new RuntimeException(\"$entityName not found: \" + id));\n}",
                type = "crud",
                description = "Find by ID"
            ))
        },

        // ---- CRUD: findAll ----
        CommentPattern(PATTERN_FIND_ALL) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "findAll",
                text = "public List<$entityName> findAll() {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findAll();\n}",
                type = "crud",
                description = "Find all"
            ))
        },

        // ---- CRUD: save ----
        CommentPattern(PATTERN_SAVE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "save",
                text = "public $entityName save($entityName entity) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.save(entity);\n}",
                type = "crud",
                description = "Save entity"
            ))
        },

        // ---- CRUD: update ----
        CommentPattern(PATTERN_UPDATE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "update",
                text = "public $entityName update(Long id, $entityName entity) {\n    $entityName existing = findById(id);\n    // copy properties\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.save(existing);\n}",
                type = "crud",
                description = "Update entity"
            ))
        },

        // ---- CRUD: delete ----
        CommentPattern(PATTERN_DELETE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "deleteById",
                text = "public void deleteById(Long id) {\n    ${entityName.replaceFirstChar { it.lowercase() }}Repository.deleteById(id);\n}",
                type = "crud",
                description = "Delete by ID"
            ))
        },

        // ---- CRUD: pagination ----
        CommentPattern(PATTERN_PAGE) { _, ctx ->
            val entityName = ctx.className.removeSuffix("Repository").removeSuffix("Dao").removeSuffix("Service")
            listOf(CodeSuggestion(
                name = "findAll(Pageable)",
                text = "public Page<$entityName> findAll(Pageable pageable) {\n    return ${entityName.replaceFirstChar { it.lowercase() }}Repository.findAll(pageable);\n}",
                type = "crud",
                description = "Paginated query"
            ))
        },

        // ---- Autowired / Injection ----
        CommentPattern(PATTERN_AUTOWIRED) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Autowired",
                text = "@Autowired\nprivate ${"$1"} ${"$2"};",
                type = "spring",
                description = "Autowired field injection"
            ), CodeSuggestion(
                name = "constructor injection",
                text = "private final ${"$1"} ${"$2"};\n\npublic ${ctx.className}(final ${"$1"} ${"$2"}) {\n    this.${"$2"} = ${"$2"};\n}",
                type = "spring",
                description = "Constructor injection"
            ))
        },

        // ---- Mapper / Convert ----
        CommentPattern(PATTERN_MAPPER) { _, ctx ->
            val sourceType = if (ctx.className.contains("DTO", ignoreCase = true) || ctx.className.contains("Vo", ignoreCase = true))
                ctx.className.removeSuffix("DTO").removeSuffix("Vo") else "${ctx.className}DTO"
            val targetType = if (sourceType == ctx.className) "${ctx.className}DTO" else ctx.className
            listOf(CodeSuggestion(
                name = "toDTO",
                text = "public $targetType to${targetType.replaceFirstChar { it.uppercase() }}($sourceType source) {\n    $targetType target = new $targetType();\n    // TODO: map fields\n    return target;\n}",
                type = "conversion",
                description = "Entity → DTO converter"
            ), CodeSuggestion(
                name = "toEntity",
                text = "public $sourceType to${sourceType.replaceFirstChar { it.uppercase() }}($targetType dto) {\n    $sourceType entity = new $sourceType();\n    // TODO: map fields\n    return entity;\n}",
                type = "conversion",
                description = "DTO → Entity converter"
            ))
        },

        // ---- Validate ----
        CommentPattern(PATTERN_VALIDATE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "validate",
                text = "public void validate(${"$1"} input) {\n    if (input == null) {\n        throw new IllegalArgumentException(\"input must not be null\");\n    }\n    // TODO: add validation rules\n}",
                type = "validation",
                description = "Validation method"
            ))
        },

        // ---- Configuration / @Bean ----
        CommentPattern(PATTERN_CONFIG) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Bean",
                text = "@Bean\npublic ${"$1"} ${"$2"}() {\n    return new ${"$1"}();\n}",
                type = "spring",
                description = "Bean definition"
            ))
        },

        // ---- Cache ----
        CommentPattern(PATTERN_CACHE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Cacheable",
                text = "@Cacheable(value = \"${"$1"}\", key = \"#${"$2"}\")\npublic ${"$3"} ${"$4"}(${"$5"} ${"$6"}) {\n    return null;\n}",
                type = "spring",
                description = "Cachable method"
            ))
        },

        // ---- Async ----
        CommentPattern(PATTERN_ASYNC) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Async",
                text = "@Async\npublic void executeAsync() {\n    // TODO: async logic\n}",
                type = "spring",
                description = "Async method"
            ))
        },

        // ---- Scheduled ----
        CommentPattern(PATTERN_SCHEDULE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Scheduled(cron)",
                text = "@Scheduled(cron = \"0 0/5 * * * ?\")\npublic void scheduledTask() {\n    // TODO: scheduled logic\n}",
                type = "spring",
                description = "Scheduled task"
            ), CodeSuggestion(
                name = "@Scheduled(fixedRate)",
                text = "@Scheduled(fixedRate = 5000)\npublic void scheduledTask() {\n    // TODO: scheduled logic\n}",
                type = "spring",
                description = "Scheduled task (fixed rate)"
            ))
        },

        // ---- Thread-safe ----
        CommentPattern(PATTERN_THREAD_SAFE) { _, ctx ->
            listOf(CodeSuggestion(
                name = "synchronized",
                text = "public synchronized void execute() {\n    // TODO: thread-safe logic\n}",
                type = "concurrency",
                description = "Synchronized method"
            ), CodeSuggestion(
                name = "ReentrantLock",
                text = "private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();\n\npublic void execute() {\n    lock.lock();\n    try {\n        // TODO: thread-safe logic\n    } finally {\n        lock.unlock();\n    }\n}",
                type = "concurrency",
                description = "ReentrantLock"
            ))
        },

        // ---- Utility / Helper ----
        CommentPattern(PATTERN_UTILITY) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Static utility",
                text = "private ${ctx.className}() {}\n\npublic static ${"$1"} ${"$2"}(${"$3"} ${"$4"}) {\n    // TODO: utility logic\n    return null;\n}",
                type = "utility",
                description = "Static utility method"
            ))
        },

        // ---- DTO / VO ----
        CommentPattern(PATTERN_DTO) { _, ctx ->
            listOf(CodeSuggestion(
                name = "DTO class",
                text = "public class ${ctx.className}DTO {\n    \n}",
                type = "dto",
                description = "Inner DTO class"
            ), CodeSuggestion(
                name = "VO class",
                text = "public class ${ctx.className}VO {\n    \n}",
                type = "dto",
                description = "Inner VO class"
            ))
        },

        // ---- REST API ----
        CommentPattern(PATTERN_REST_API) { _, ctx ->
            listOf(CodeSuggestion(
                name = "GET endpoint",
                text = "@GetMapping\npublic ResponseEntity<Object> getAll() {\n    return ResponseEntity.ok();\n}",
                type = "rest",
                description = "GET endpoint"
            ), CodeSuggestion(
                name = "POST endpoint",
                text = "@PostMapping\npublic ResponseEntity<Object> create(@Valid @RequestBody Object body) {\n    return ResponseEntity.ok(body);\n}",
                type = "rest",
                description = "POST endpoint"
            ), CodeSuggestion(
                name = "DELETE endpoint",
                text = "@DeleteMapping(\"/{id}\")\npublic ResponseEntity<Void> delete(@PathVariable Long id) {\n    // TODO: delete logic\n    return ResponseEntity.noContent().build();\n}",
                type = "rest",
                description = "DELETE endpoint"
            ))
        },

        // ---- Custom Exception ----
        CommentPattern(PATTERN_EXCEPTION) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Exception class",
                text = "public class ${ctx.className}Exception extends RuntimeException {\n    public ${ctx.className}Exception(String message) {\n        super(message);\n    }\n    public ${ctx.className}Exception(String message, Throwable cause) {\n        super(message, cause);\n    }\n}",
                type = "exception",
                description = "Custom exception"
            ))
        },

        // ---- Enum ----
        CommentPattern(PATTERN_ENUM) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Enum",
                text = "public enum ${ctx.className} {\n    VALUE1,\n    VALUE2,\n    VALUE3;\n}",
                type = "enum",
                description = "Enum class"
            ))
        },

        // ---- Test ----
        CommentPattern(PATTERN_TEST) { _, ctx ->
            listOf(CodeSuggestion(
                name = "@Test",
                text = "@Test\npublic void test${"$1"}() {\n    // given\n    \n    // when\n    \n    // then\n    \n}",
                type = "test",
                description = "JUnit test method"
            ))
        },

        // ---- Stream / Lambda ----
        CommentPattern(PATTERN_STREAM) { _, ctx ->
            listOf(CodeSuggestion(
                name = "Stream filter",
                text = "list.stream()\n    .filter(item -> item != null)\n    .map(item -> item.toString())\n    .collect(Collectors.toList());",
                type = "stream",
                description = "Stream filter/map/collect"
            ))
        },
    )

    // ==================== 类型定义 ====================

    /** 注释匹配到的模式 */
    data class CommentPattern(
        val regex: Regex,
        val generator: (MatchResult, CommentContext) -> List<CodeSuggestion>
    )

    /** 代码建议 */
    data class CodeSuggestion(
        val name: String,
        val text: String,
        val type: String,
        val description: String = ""
    )

    /** 注释分析上下文 */
    data class CommentContext(
        val commentText: String,
        val psiClass: PsiClass?,
        val psiMethod: PsiMethod?,
        val psiField: PsiField?,
        val project: com.intellij.openapi.project.Project
    ) {
        val className: String get() = psiClass?.name ?: "Unknown"

        /** 在 PSI 类中查找字段类型 */
        fun findFieldType(fieldName: String): String? {
            val cls = psiClass ?: return null
            // 直接匹配
            val field = cls.fields.find { it.name.equals(fieldName, ignoreCase = true) }
            if (field != null) return field.type?.presentableText
            // 模糊匹配
            val fuzzy = cls.fields.find { it.name?.contains(fieldName, ignoreCase = true) == true }
            return fuzzy?.type?.presentableText
        }
    }

    // ==================== 核心逻辑 ====================

    /**
     * 分析光标前的注释文本，返回匹配的代码建议。
     */
    fun analyzeComments(element: PsiElement, offset: Int): List<CodeSuggestion> {
        val commentText = extractCommentBefore(element, offset) ?: return emptyList()
        if (commentText.isBlank()) return emptyList()

        val ctx = buildContext(element)
        LOG.debug("Comment detected: \"$commentText\" in class ${ctx.className}")

        val allSuggestions = mutableListOf<CodeSuggestion>()

        for (pattern in patterns) {
            val match = pattern.regex.find(commentText)
            if (match != null) {
                try {
                    val suggestions = pattern.generator(match, ctx)
                    allSuggestions.addAll(suggestions)
                } catch (e: Exception) {
                    LOG.warn("Comment pattern '${pattern.regex}' generated error", e)
                }
            }
        }

        // 如果有命中模式但无建议（如 getter 但字段不存在），返回空
        return allSuggestions
    }

    /**
     * 检查光标位置是否在注释后（注释行后紧跟空行，光标在空行上）。
     */
    fun isAfterComment(element: PsiElement, offset: Int): Boolean {
        val doc = (element.containingFile as? PsiJavaFile)?.viewProvider?.document ?: return false
        val lineNum = doc.getLineNumber(offset)
        if (lineNum <= 0) return false

        // 检查前一行是否是注释
        for (i in lineNum - 1 downTo maxOf(0, lineNum - 5)) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
            if (lineText.isEmpty()) continue // 跳过空行
            if (lineText.startsWith("//") || lineText.startsWith("*") || lineText.startsWith("/*") || lineText.startsWith("/**")) {
                return true
            }
            // 如果遇到非注释非空行，停止向上搜索
            return false
        }
        return false
    }

    /**
     * 获取光标前的最后一段注释文本。
     * 支持：// 单行注释、/星 / 块注释、/星星 / 文档注释
     */
    private fun extractCommentBefore(element: PsiElement, offset: Int): String? {
        val file = element.containingFile
        val doc = (file as? PsiJavaFile)?.viewProvider?.document ?: return null
        val lineNum = doc.getLineNumber(offset)
        if (lineNum <= 0) return null

        val sb = StringBuilder()

        // 从光标行向上扫描，收集注释内容
        for (i in lineNum - 1 downTo 0) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()

            if (lineText.isEmpty()) {
                // 遇到空行：如果是第一个空行继续，否则停止
                if (sb.isNotEmpty()) break
                continue
            }

            if (lineText.startsWith("//")) {
                sb.insert(0, lineText.removePrefix("//").trim() + " ")
            } else if (lineText.startsWith("*/") || lineText.startsWith("**/")) {
                // 块注释结束，收集块内内容
                collectBlockComment(doc, i, sb)
                break
            } else if (lineText.startsWith("*")) {
                // Javadoc 或块注释中间行
                sb.insert(0, lineText.removePrefix("*").trim() + " ")
            } else if (lineText.startsWith("/*") || lineText.startsWith("/**")) {
                // 块注释开始行
                val content = lineText.removePrefix("/**").removePrefix("/*").trimEnd('*').trimEnd('/').trim()
                if (content.isNotEmpty()) sb.insert(0, content + " ")
                break
            } else {
                // 非注释代码行，停止
                break
            }
        }

        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    /** 收集块注释/文档注释的全部内容 */
    private fun collectBlockComment(doc: com.intellij.openapi.editor.Document, endLine: Int, sb: StringBuilder) {
        for (i in endLine downTo 0) {
            val lineStart = doc.getLineStartOffset(i)
            val lineEnd = doc.getLineEndOffset(i)
            val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()

            if (lineText.startsWith("/*") || lineText.startsWith("/**")) {
                val content = lineText.removePrefix("/**").removePrefix("/*").trimEnd('*').trimEnd('/').trim()
                if (content.isNotEmpty()) sb.insert(0, content + " ")
                break
            }
            if (lineText.startsWith("*")) {
                sb.insert(0, lineText.removePrefix("*").trim() + " ")
            }
        }
    }

    /** 构建注释上下文（从 PSI 元素提取类/方法/字段信息） */
    private fun buildContext(element: PsiElement): CommentContext {
        return CommentContext(
            commentText = "",
            psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java),
            psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java),
            psiField = PsiTreeUtil.getParentOfType(element, PsiField::class.java),
            project = element.project
        )
    }

    /**
     * 将 CodeSuggestion 转换为 CompletionCandidate。
     */
    fun toCompletionCandidates(suggestions: List<CodeSuggestion>): List<StaticAnalysisCompletionProvider.CompletionCandidate> {
        return suggestions.map { s ->
            StaticAnalysisCompletionProvider.CompletionCandidate(
                displayName = s.name,
                insertText = s.text,
                typeText = s.type,
                icon = when (s.type) {
                    "getter", "setter" -> com.intellij.icons.AllIcons.Nodes.PropertyRead
                    "constructor" -> com.intellij.icons.AllIcons.Nodes.Method
                    "design-pattern" -> com.intellij.icons.AllIcons.Nodes.Class
                    "crud" -> com.intellij.icons.AllIcons.Nodes.Method
                    "spring" -> com.intellij.icons.AllIcons.Nodes.Method
                    "conversion" -> com.intellij.icons.AllIcons.Nodes.Method
                    "validation" -> com.intellij.icons.AllIcons.Nodes.Method
                    "logging" -> com.intellij.icons.AllIcons.Nodes.Variable
                    "concurrency" -> com.intellij.icons.AllIcons.Nodes.Method
                    "rest" -> com.intellij.icons.AllIcons.Nodes.Method
                    "test" -> com.intellij.icons.AllIcons.Nodes.Method
                    "stream" -> com.intellij.icons.AllIcons.Nodes.Method
                    "dto" -> com.intellij.icons.AllIcons.Nodes.Class
                    "exception" -> com.intellij.icons.AllIcons.Nodes.Class
                    "enum" -> com.intellij.icons.AllIcons.Nodes.Enum
                    "utility" -> com.intellij.icons.AllIcons.Nodes.Method
                    else -> com.intellij.icons.AllIcons.Nodes.Variable
                },
                description = s.description.ifEmpty { s.type },
                relevanceScore = 0.95f // 注释匹配的优先级高
            )
        }
    }
}

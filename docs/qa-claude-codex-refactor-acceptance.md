# Claude/Codex Q&A Refactor Acceptance / Claude/Codex Q&A 改造验收

Date / 日期: 2026-08-19

## Scope / 范围

This refactor improves Q&A behavior for Claude and Codex without changing Agent mode permissions. It separates persisted conversation history from request-only project and attachment context, adds provider-native image requests, bounds input context, hardens streaming errors, and restricts Q&A project tools to read-only operations.

本次改造在不扩大 Agent 模式权限的前提下改善 Claude 与 Codex 的 Q&A 行为：分离持久会话历史和本轮项目/附件上下文，增加原生图片请求，限制输入上下文，完善流式错误处理，并将 Q&A 项目工具严格限制为只读操作。

## Acceptance Matrix / 验收矩阵

| ID | Requirement / 要求 | Code evidence / 代码证据 | Automated evidence / 自动化证据 |
|---|---|---|---|
| QA-01 | Normal Q&A does not implicitly scan the project / 普通 Q&A 不隐式扫描项目 | `ChatPanel.buildQaSystemPrompt`, `ChatPanel.respondDirectly` | Diff inspection / 差异审计 |
| QA-02 | Full scan performs one bounded search / 全文扫描只执行一次受控检索 | `ChatPanel.sendQAScanMessage`, `SearchCoordinator.search` | Diff inspection / 差异审计 |
| QA-03 | Claude/Codex full scan can use only local read-only tools / 全文扫描仅允许本地只读工具 | `ToolUseEngine`, `LlmProviderCapabilities.readOnlyTools` | Diff inspection / 差异审计 |
| QA-04 | Attachments, source context, and scan results are request-local / 附件、源码和扫描结果仅用于本轮请求 | `QaRequestComposer`, `ChatPanel.composeQaRequest` | `QaRequestComposerTest` |
| QA-05 | Recent complete turns and the current question survive budget trimming / 裁剪时保留最近完整轮次和当前问题 | `QaRequestComposer` | `QaRequestComposerTest` |
| QA-06 | Claude receives Anthropic image content blocks / Claude 接收 Anthropic 原生图片块 | `DeepSeekApiClient.buildAnthropicBody` | `ProviderRequestBodyTest` |
| QA-07 | Codex receives `input_image` and reasoning effort without `temperature` / Codex 接收原生图片和推理强度且不发送温度参数 | `DeepSeekApiClient.buildCodexResponsesBody` | `ProviderRequestBodyTest` |
| QA-08 | Old sessions with no structured parts remain readable / 旧会话缺少结构化 parts 时仍可读取 | Nullable `ChatMessage.parts` and `orEmpty()` consumers / 可空字段及空安全消费 | `QaRequestComposerTest` plus compile / 测试及编译 |
| QA-09 | Streaming state exists before the HTTP source is attached / HTTP 流建立前先注册可取消状态 | `DeferredEventSource`, `ChatPanel.respondDirectly` | `DeferredEventSourceTest` |
| QA-10 | Empty, failed, incomplete, malformed, and prematurely closed streams fail explicitly / 空响应、失败、不完整、非法事件和异常关闭均显式报错 | Anthropic and Codex stream handlers in `DeepSeekApiClient` | Request-body unit tests plus code inspection / 请求体测试及代码审计 |
| QA-11 | One retry is allowed only before the first token for transient failures / 仅在首 Token 前对瞬时错误重试一次 | `ChatPanel.isRetryableQaError`, `ChatPanel.respondDirectly` | Code inspection / 代码审计 |
| QA-12 | Partial answers are retained when a later stream error occurs / 流式中途失败时保留已有回答 | `ChatPanel.respondDirectly` error callback | Code inspection; endpoint scenario pending / 代码审计，端点场景待验证 |
| QA-13 | Completed answers keep the full Token row visible when the user follows the bottom / 用户跟随底部时完整显示 Token 行 | `ChatAutoScrollController`, `ChatPanel.scrollToBottom` | `ChatAutoScrollControllerTest` plus UI code inspection / 状态测试及 UI 代码审计 |
| QA-14 | Token usage survives session redraws and old sessions remain compatible / Token 用量可随会话重绘恢复且兼容旧会话 | `ChatMessage.usage`, `ChatPanel.renderMessageRange` | `ChatMessageUsageTest` |

## Automated Verification / 自动化验证

```powershell
.\gradlew.bat test --stacktrace
git diff --check
```

Expected focused suites / 重点测试集:

- `ProviderRequestBodyTest`: Anthropic and Codex payload contracts / 请求体协议
- `QaRequestComposerTest`: transient context, history trimming, native image isolation / 临时上下文、历史裁剪、原生图片隔离
- `DeferredEventSourceTest`: cancellation and retry source replacement / 取消及重试连接替换
- Existing attachment and CLI tests / 既有附件及 CLI 测试

## Manual Provider Scenarios / Provider 实机验收

1. Send PNG, JPEG, and WebP images to Claude and Codex in Q&A mode and verify the answer uses image details.
2. Upload PDF, DOCX, XLSX, and PPTX files, ask a document-specific question, then ask an unrelated follow-up and verify document text does not leak into the next request.
3. Compare normal Q&A with full scan: normal Q&A must not show project scanning; full scan may use only `grep`, `glob`, and `read`.
4. Trigger HTTP 429 or a transient 5xx before the first token and verify one retry; trigger an error after output starts and verify no retry and preservation of partial output.
5. Load a conversation saved before version 2.7.5 and send a follow-up to verify nullable structured parts remain compatible.

## Residual Risk / 剩余风险

Automated tests validate local composition and lifecycle behavior but do not call live Anthropic, Codex, or compatible proxy endpoints. Provider-specific proxy differences, image size limits, OAuth state, and real SSE timing still require the manual scenarios above.

自动化测试覆盖本地请求组合与生命周期，但不会调用真实 Anthropic、Codex 或兼容代理端点。代理协议差异、图片大小限制、OAuth 状态以及真实 SSE 时序仍需通过上述实机场景验证。

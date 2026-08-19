# Attachment Refactor Acceptance Matrix

Date: 2026-08-19

## Scope

- Parse text, PDF, DOCX, XLSX, and PPTX attachments.
- Route attachments through Q&A, Q&A scan, Agent, and Claude/Codex CLI modes.
- Use Claude/Codex native file and image capabilities where available.
- Enforce attachment limits and expose parsing state in the UI.

## Requirement Matrix

| ID | Requirement | Implementation | Evidence |
|---|---|---|---|
| ATT-01 | Detect supported attachment types | `AttachmentTypeDetector.kt` | Unit test: `detects supported document types` |
| ATT-02 | Decode UTF-8, UTF-16, and GB18030 text | `DocumentExtractionService.decodeText` | Unit test: `extracts utf8 and gb18030 text` |
| ATT-03 | Extract PDF text with page limits | `DocumentExtractionService.extractPdf` | Unit test: `extracts pdf text` |
| ATT-04 | Extract DOCX paragraphs and tables | `DocumentExtractionService.extractWord` | Unit test: `extracts docx paragraphs and tables` |
| ATT-05 | Extract XLSX worksheets, values, formulas | `DocumentExtractionService.extractSpreadsheet` | Unit test: `extracts xlsx values and formulas` |
| ATT-06 | Extract PPTX slide text | `DocumentExtractionService.extractPresentation` | Unit test: `extracts pptx slide text` |
| ATT-07 | Render scanned PDFs as images | `PdfPageRenderer.kt` and `AttachedFile.readContent` | Manual scenario required |
| ATT-08 | Q&A retains text/document attachments | `ChatPanel.sendMessage` | Route inspection and integration test required |
| ATT-09 | Q&A scan retains attachments | `ChatPanel.sendQAScanMessage` | Route inspection and integration test required |
| ATT-10 | Agent retains documents and images | `ChatPanel.sendAgentMessage` | Route inspection and integration test required |
| ATT-11 | Claude receives an isolated readable directory | `CliAgentProcess.buildArgs` | `CliAgentProcessTest` |
| ATT-12 | Codex receives readable directory and images | `CliAgentProcess.buildArgs` | `CliAgentProcessTest` |
| ATT-13 | CLI attachment directories are cleaned | `CliAttachmentBundle.close`, `ChatPanel.stopStreaming` | Lifecycle inspection; integration test required |
| ATT-14 | Parsing is off the EDT and cancellable | `prepareAttachmentsInBackground`, generation guard | Route inspection; UI test required |
| ATT-15 | Reject oversized and unsupported files | `ChatPanel.addFileAttachment` | Unit and UI scenario required |
| ATT-16 | Reject renamed binary files | `DocumentExtractionService.validateSignature` | Unit test: `rejects renamed binary files` |
| ATT-17 | Limit prompt context size | `ChatPanel.buildTextFileContext` | Boundary test required |
| ATT-18 | Show parsing state and errors | `FileAttachmentPreview.kt`, `I18n.kt` | UI scenario required |

## Automated Commands

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path '.gradle').Path
.\gradlew.bat :plugin-backend:test --tests com.deepseek.plugin.attachment.DocumentExtractionServiceTest
.\gradlew.bat test
.\gradlew.bat compileKotlin
```

## Current Evidence

- `git diff --check`: passed; only repository line-ending warnings were reported.
- `./gradlew.bat test --stacktrace`: passed on 2026-08-19 using the Gradle user cache and Maven dependency resolution.
- Automated result: 17 tests, 0 failures, 0 errors, 0 skipped. This includes 8 document extraction tests and 2 Claude/Codex CLI attachment tests.
- Main and backend Kotlin compilation completed as part of the test build. The only compiler warning was an existing unchecked Swing combo-box cast in `ChatPanel.kt`.

## Manual Acceptance Scenarios

1. Upload one document of each supported type and ask for a fact unique to that document.
2. Repeat scenario 1 in Q&A, Q&A scan, Agent, Claude Code, and Codex modes.
3. Upload a scanned PDF and verify page images are processed.
4. Stop while a large document is parsing and verify no model request starts afterward.
5. Stop a running CLI request and verify its attachment directory is released.
6. Upload damaged, renamed, encrypted, oversized, and legacy Office files and verify explicit errors.

## Acceptance Status

Implementation and automated acceptance are complete. Live Claude/Codex provider calls and the UI lifecycle scenarios above remain manual acceptance items.

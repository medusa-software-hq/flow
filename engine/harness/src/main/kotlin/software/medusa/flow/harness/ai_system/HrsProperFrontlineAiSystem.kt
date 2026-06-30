package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchCommand
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutCommand
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationLog
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.dump
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.exploreKeyword
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.load
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.readyKeyword
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.dump
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.load
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderDirectoryTree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderFiles
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

class HrsProperFrontlineAiSystem(
    private val openaiClient: OaiConfiguredClient,
) : HrsFrontlineAiSystem {
  companion object {
    private val introText =
        """
        You are conversing with an automated system. Don't ask questions and follow the requested format literally.

        Because of cache optimizations, some information in the chat history may seem to appear non-chronologically.

        You will now be provided with The Task.
        """
            .trimIndent()

    private val scoutResponseFormatIntroText =
        """
        # Scout Response Format (Markdown-based)

        A Scout Response must start with an ATX heading: either `# $exploreKeyword` or `# $readyKeyword`.

        Use `# $exploreKeyword` while the worktree is not yet fully explored for The Task. Follow the heading with a natural-language rationale, then — as the last Markdown block — a tree of path actions.

        The tree is a nested bullet list. Each item is an absolute path written as inline code (starting with `/`; it may span several directories, e.g. `/dir/sub/file.txt`), optionally followed by a verb:

        - OPEN — open this file.
        - EXPAND — expand this collapsed directory.
        - An item with a verb is a leaf and must NOT have a sublist; an item without a verb means "go deeper" and must have a sublist of two or more items.

        Sample $exploreKeyword message:
        """
            .trimIndent()

    private val scoutResponseFormatIntermediateText =
        """
        A $readyKeyword message should be used when the scouting is completed. It's not followed by any rationale.
          
        Proper $readyKeyword message:
        """
            .trimIndent()

    private val scoutingIntroText =
        """
        Scout the worktree. Open the files that are likely to be relevant to The Task.

        Expand collapsed directories that are likely to be relevant to The Task (note: all of them might be already expanded).

        Respond in the Scout Response Format.
        """
            .trimIndent()

    private val scoutContinueCommandExample =
        ScoutCommand.Continue(
            rationale =
                MdElement(
                    blocks =
                        listOf(
                            MdBlock.Paragraph.of(
                                "Here goes the natural language rationale. In the actual run, explain the reasoning behind the request.",
                            ),
                        ),
                ),
            requestedAdjustment =
                VedWorktreeAdjustment(
                    rootDirectoryAdjustment =
                        VedDirectoryAdjustment.Dive(
                            childAdjustmentByName =
                                mapOf(
                                    UfsName.Literal("file1.txt") to VedFileAdjustment.Open,
                                    UfsName.Literal("dir1") to
                                        VedDirectoryAdjustment.Dive(
                                            childAdjustmentByName =
                                                mapOf(
                                                    UfsName.Literal("dir2") to
                                                        VedDirectoryAdjustment.Dive(
                                                            childAdjustmentByName =
                                                                mapOf(
                                                                    UfsName.Literal("dir3") to
                                                                        VedDirectoryAdjustment.Dive(
                                                                            childAdjustmentByName =
                                                                                mapOf(
                                                                                    UfsName.Literal(
                                                                                        "file2.cpp",
                                                                                    ) to
                                                                                        VedFileAdjustment
                                                                                            .Open,
                                                                                ),
                                                                        ),
                                                                ),
                                                        ),
                                                    UfsName.Literal("collapsedDir1") to
                                                        VedDirectoryAdjustment.Expand,
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    private val solutionResponseFormatIntroText =
        """
        # Patch Response Format (Markdown-based)

        A Patch Response should start with a `# PATCH` heading. Under it, add one `## ` heading per
        file you change, titled with the file's absolute path as inline code (e.g. `/src/Main.kt`).
        For each file, add one `### ` heading per edit:

        - `INSERT BEFORE n` — insert new lines before original line `n`. To append at the end of
          a file, use one past the last line (e.g. `INSERT BEFORE 21` for a 20-line file).
        - `UPDATE a-b` — replace the original-line range a..b (inclusive on both sides).
        - `DELETE a-b` — delete the original-line range a..b (inclusive on both sides).

        Follow every heading except `DELETE` with a fenced code block holding the exact new lines.
        All line numbers are 1-based and refer to the file's original content as shown. Edits must
        neither overlap nor touch: leave at least one unchanged line between two edits, or combine
        adjacent changes into a single edit. Only currently open files may be edited.

        Sample PATCH message:
        """
            .trimIndent()

    private val solutionImplementationIntroText =
        """
        Implement the solution to The Task by editing the opened files shown above.

        Respond in the Patch Response Format.
        """
            .trimIndent()

    private val mainKtExamplePatch =
        VedFilePatch(
            txtPatch =
                TxtPatch(
                    fragmentByOldLineIndexRange =
                        mapOf(
                            TxtLineIndexRange.empty(
                                startIndex =
                                    TxtLineIndex.ofOneBased(
                                        1,
                                    ),
                            ) to
                                TxtPatch.Fragment(
                                    newContent =
                                        TxtBlock.of(
                                            "import kotlin.math.max",
                                        ),
                                ),
                            TxtLineIndexRange.of(
                                startIndex =
                                    TxtLineIndex.ofOneBased(
                                        11,
                                    ),
                                length = 2,
                            ) to
                                TxtPatch.Fragment(
                                    newContent =
                                        TxtBlock.of(
                                            "    val result = max(a, b)",
                                            "    return result",
                                        ),
                                ),
                        ),
                ),
        )

    private val patchCommandExample =
        PatchCommand(
            solutionPatch =
                VedWorktreePatch(
                    rootDirectoryPatch =
                        VedDirectoryPatch(
                            childPatchByName =
                                mapOf(
                                    UfsName.Literal("src") to
                                        VedDirectoryPatch(
                                            childPatchByName =
                                                mapOf(
                                                    UfsName.Literal("Main.kt") to
                                                        mainKtExamplePatch,
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    private fun renderRequest(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
        tailMessages: List<OaiMessage>,
    ): OaiConfiguredClient.CompletionRequest {
      val prefix =
          renderPrefix(
              taskDescription = taskDescription,
              editorWorktree = editorWorktree,
          )

      val chat =
          OaiChat(
              messages =
                  prefix +
                      listOf(
                          OaiMessage(
                              role = OaiRole.System,
                              text = editorWorktree.renderDirectoryTree().render(),
                          ),
                      ) +
                      tailMessages,
          )

      return OaiConfiguredClient.CompletionRequest(
          input = chat,
          reasoningEffort = OaiConfiguredClient.ReasoningEffort.High,
      )
    }

    private fun renderPrefix(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
    ): List<OaiMessage> =
        listOf(
            OaiMessage(
                role = OaiRole.System,
                text = introText,
            ),
            OaiMessage(
                role = OaiRole.User,
                text = taskDescription.body.render(),
            ),
            OaiMessage(
                role = OaiRole.System,
                text = editorWorktree.renderFiles().render(),
            ),
        )
  }

  override suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      scoutingObserver: HrsTaskCompleter.ScoutingObserver,
  ): ScoutCommand {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.System,
                        text = scoutResponseFormatIntroText,
                    ),
                    OaiMessage(
                        role = OaiRole.System,
                        text = scoutContinueCommandExample.dump().render(),
                    ),
                    OaiMessage(
                        role = OaiRole.System,
                        text = scoutResponseFormatIntermediateText,
                    ),
                    OaiMessage(
                        role = OaiRole.System,
                        text = ScoutCommand.Stop.dump().render(),
                    ),
                    OaiMessage(
                        role = OaiRole.User,
                        text = scoutingIntroText,
                    ),
                ) +
                    scoutingLog.logEntries.flatMap { logEntry ->
                      listOf(
                          OaiMessage(
                              role = OaiRole.Assistant,
                              text = logEntry.continueCommand.dump().render(),
                          ),
                          OaiMessage(
                              role = OaiRole.System,
                              text = logEntry.systemResponse.dump().render(),
                          ),
                      )
                    },
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    scoutingObserver.observeRawResponse(response = response)

    val loadedResult =
        ScoutCommand.load(
            document = MdDocument.parse(markdownSource = response.responseText),
        )

    return loadedResult
  }

  override suspend fun implementSolution(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      solutionImplementationLog: SolutionImplementationLog,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ): PatchCommand {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.System,
                        text = solutionResponseFormatIntroText,
                    ),
                    OaiMessage(
                        role = OaiRole.System,
                        text = patchCommandExample.dump().render(),
                    ),
                    OaiMessage(
                        role = OaiRole.User,
                        text = solutionImplementationIntroText,
                    ),
                ) +
                    solutionImplementationLog.logEntries.flatMap { logEntry ->
                      listOf(
                          OaiMessage(
                              role = OaiRole.Assistant,
                              text = logEntry.patchCommand.dump().render(),
                          ),
                          OaiMessage(
                              role = OaiRole.System,
                              text =
                                  MdDocument(
                                          rootChapter =
                                              renderPatchSystemResponse(
                                                  systemResponse = logEntry.systemResponse,
                                              ),
                                      )
                                      .render(),
                          ),
                          OaiMessage(
                              role = OaiRole.User,
                              text =
                                  "Try to fix the found issues. Respond in the Patch Response Format.",
                          ),
                      )
                    },
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    solutionImplementationObserver.observeRawResponse(response = response)

    return PatchCommand.load(
        document = MdDocument.parse(markdownSource = response.responseText),
    )
  }

  private fun renderPatchSystemResponse(
      systemResponse: PatchCommand.SystemResponse,
  ): MdChapter {
    val approvalTimestamp = systemResponse.approvalTimestamp
    val projectFailure = systemResponse.failureReport.failure

    val stageText =
        when (systemResponse.failureReport.stage) {
          ProjectFailureReport.Stage.Analysis -> "Analysis"
          ProjectFailureReport.Stage.Testing -> "Testing"
        }

    return MdChapter(
        title = MdInlineContent.of("Patch applied"),
        element =
            MdElement(
                blocks =
                    listOf(
                        MdBlock.Paragraph.of(
                            "The worktree was patched according to your request.",
                        ),
                        MdBlock.Paragraph.of("Timestamp: t = ${approvalTimestamp.t}"),
                        MdBlock.Paragraph.of(
                            inlineNodes =
                                listOf(
                                    MdInlineNode.Strong.of("NOTE:"),
                                    MdInlineNode.Text("The freshly patched files are visible "),
                                    MdInlineNode.Emphasis.of("above"),
                                    MdInlineNode.Text("this message, in the Worktree section."),
                                ),
                        ),
                    ),
            ),
        subChapters =
            listOf(
                MdChapter(
                    title = MdInlineContent.of("Found issues"),
                    element =
                        MdElement(
                            blocks =
                                listOf(
                                    MdBlock.Paragraph.of("Phase: $stageText"),
                                ),
                        ),
                    subChapters =
                        projectFailure.failureByModulePath.map { (modulePath, moduleFailure) ->
                          MdChapter.leaf(
                              title =
                                  MdInlineContent(
                                      inlineNodes =
                                          listOf(
                                              MdInlineNode.Text("Module "),
                                              MdInlineNode.Code(
                                                  modulePath.toUnixAbsolutePathString()
                                              ),
                                              MdInlineNode.Text(":"),
                                          ),
                                  ),
                              element =
                                  MdElement(
                                      blocks =
                                          listOf(
                                              MdBlock.CodeBlock(
                                                  code = moduleFailure.diagnosticOutput
                                              ),
                                          ),
                                  ),
                          )
                        },
                ),
            ),
    )
  }
}

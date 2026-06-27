package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutRequest
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationResult
import software.medusa.flow.harness.ai_system.ScoutRequest_utils.dump
import software.medusa.flow.harness.ai_system.ScoutingResult_utils.dump
import software.medusa.flow.harness.ai_system.ScoutingResult_utils.load
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.dump
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.load
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.render
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * A single "frontline" agent that drives both scouting and solution implementation directly, with
 * no fictional helper roles. Each phase is one LLM turn: the model is taught an ad-hoc Markdown
 * format, replies in it, and we parse that reply deterministically (see [ScoutingResult_utils] and
 * [SolutionImplementationResult_utils]).
 */
class HrsProperFrontlineAiSystem(
    private val openaiClient: OaiConfiguredClient,
) : HrsFrontlineAiSystem {
  companion object {
    private val scoutingJobIntroductionText =
        """
        You are scouting a worktree to make sure every file relevant to The Task is opened.

        The worktree above shows the project tree. Some directories are collapsed (their contents
        are hidden) and some files are closed (their contents are hidden). You uncover them
        gradually, one turn at a time.

        Reply with exactly one Markdown document in one of these two forms.

        To request more to be uncovered, reply with a `# CONTINUE` document: a short rationale,
        followed by a single nested bullet list that mirrors the worktree starting from the root
        `/`. Each entry is an inline-code name; append a trailing `/` to directory names. Mark a
        closed file you want opened with `OPEN`, and a collapsed directory you want expanded with
        `EXPAND`. Include only the branches that lead to the entities you are acting on; directories
        you merely pass through carry no marker and hold the nested list of their children.

        When every relevant file is already open, reply with just `# STOP`.

        The format looks like this:
        """
            .trimIndent()

    private val scoutRequestExample =
        ScoutRequest(
            rationale =
                MdElement(
                    blocks =
                        listOf(
                            MdBlock.Paragraph.of(
                                "The Task is about the build setup and the app entry point. I want " +
                                    "to open the root build script and the main source file, and " +
                                    "expand the resources directory to see what it holds.",
                            ),
                        ),
                ),
            requestedAdjustment =
                VedWorktreeAdjustment(
                    rootDirectoryAdjustment =
                        VedDirectoryAdjustment.Dive(
                            childAdjustmentByName =
                                mapOf(
                                    UfsName.Literal("build.gradle.kts") to VedFileAdjustment.Open,
                                    UfsName.Literal("src") to
                                        VedDirectoryAdjustment.Dive(
                                            childAdjustmentByName =
                                                mapOf(
                                                    UfsName.Literal("Main.kt") to
                                                        VedFileAdjustment.Open,
                                                    UfsName.Literal("resources") to
                                                        VedDirectoryAdjustment.Expand,
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    private val solutionImplementationJobIntroductionText =
        """
        You are implementing the solution to The Task by editing the opened files shown above.

        Reply with exactly one `# PATCH` Markdown document. Under it, add one `## ` chapter per file
        you change, titled with the file's absolute path as inline code (e.g. `` `/src/Main.kt` ``).
        Under each file, add one `### ` chapter per edit:

        - `INSERT BEFORE n` — insert new lines before original line `n`. To append at the end of a
          file, use one past the last line (e.g. `INSERT BEFORE 21` for a 20-line file).
        - `DELETE a-b` — delete the inclusive original-line range `a..b`.
        - `UPDATE a-b` — replace the inclusive original-line range `a..b`.

        For every edit except `DELETE`, follow the heading with a fenced code block holding the exact
        new lines. All line numbers are 1-based and refer to the file's original content as shown.
        Edits must neither overlap nor touch: leave at least one unchanged line between two edits, or
        combine adjacent changes into a single edit. Only files that are currently open may be edited.

        The format looks like this:
        """
            .trimIndent()

    private val solutionImplementationResultExample =
        SolutionImplementationResult(
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
                                                        VedFilePatch(
                                                            txtPatch =
                                                                TxtPatch(
                                                                    fragmentByOldLineIndexRange =
                                                                        mapOf(
                                                                            TxtLineIndexRange.empty(
                                                                                startIndex =
                                                                                    TxtLineIndex
                                                                                        .ofOneBased(
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
                                                                                    TxtLineIndex
                                                                                        .ofOneBased(
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
                                                        ),
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

      val chat = OaiChat(messages = prefix + tailMessages)

      return OaiConfiguredClient.CompletionRequest(
          input = chat,
          reasoningEffort = OaiConfiguredClient.ReasoningEffort.High,
      )
    }

    private fun renderPrefix(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
    ): List<OaiMessage> {
      val taskDescriptionDocument =
          MdDocument(
              rootChapter =
                  MdChapter.leaf(
                      title = MdInlineContent.of("The Task"),
                      element = taskDescription.body,
                  ),
          )

      val worktreeDumpDocument = MdDocument(rootChapter = editorWorktree.render())

      return listOf(
          OaiMessage(
              role = OaiRole.System,
              text = taskDescriptionDocument.render(),
          ),
          OaiMessage(
              role = OaiRole.System,
              text = worktreeDumpDocument.render(),
          ),
      )
    }
  }

  override suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      timestamp: VedTimestamp,
  ): ScoutingResult {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.User,
                        text = scoutingJobIntroductionText,
                    ),
                    OaiMessage(
                        role = OaiRole.User,
                        text =
                            ScoutingResult.Continued(scoutRequest = scoutRequestExample)
                                .dump()
                                .render(),
                    ),
                ) +
                    scoutingLog.logEntries.flatMap { logEntry ->
                      listOf(
                          OaiMessage(
                              role = OaiRole.Assistant,
                              text =
                                  ScoutingResult.Continued(scoutRequest = logEntry.scoutRequest)
                                      .dump()
                                      .render(),
                          ),
                          OaiMessage(
                              role = OaiRole.System,
                              text = logEntry.systemResponse.dump().render(),
                          ),
                      )
                    },
        )

    val responseText = openaiClient.createUnstructuredCompletion(request = request).responseText

    return ScoutingResult.load(document = MdDocument.parse(markdownSource = responseText))
  }

  override suspend fun implementSolution(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
  ): SolutionImplementationResult {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.System,
                        text = solutionImplementationJobIntroductionText,
                    ),
                    OaiMessage(
                        role = OaiRole.System,
                        text = solutionImplementationResultExample.dump().render(),
                    ),
                ),
        )

    val responseText = openaiClient.createUnstructuredCompletion(request = request).responseText

    return SolutionImplementationResult.load(
        document = MdDocument.parse(markdownSource = responseText)
    )
  }
}

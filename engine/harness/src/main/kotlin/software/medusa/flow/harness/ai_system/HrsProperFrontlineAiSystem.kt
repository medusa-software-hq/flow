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
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem.ImplementationPlan
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationLog
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderDirectoryTree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderFiles
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment

class HrsProperFrontlineAiSystem(
    private val openaiClient: OaiConfiguredClient,
) : HrsFrontlineAiSystem {
  companion object {
    private val simpleAiName = "ai"

    private val systemIntroText =
        """
        You are conversing with a very simple AI agent. Cooperate. Respond in a direct tone, in the imperative mood. Don't ask questions. Don't do more than requested. Use Markdown.
        """
            .trimIndent()

    // region Scouting prompts

    private val scoutingIntroText =
        """
        We're now ready to scout the worktree. I have direct worktree access.

        Most directories should already be expanded, but _if_ a relevant directory is collapsed, I can expand it.

        Currently, all files are closed. We have to open the files that are likely to be relevant to The Task. I can open files.

        Which directories should I expand, if any? Which files should I open?

        (Quite often, a single round is enough, but we can make more rounds if necessary)
        """
            .trimIndent()

    private fun renderScoutingFollowupDocument(
        systemResponse: ScoutMessage.SystemResponse,
    ): MdDocument {
      val openedFilePaths = mutableListOf<String>()
      val expandedDirectoryPaths = mutableListOf<String>()

      collectAdjustmentPaths(
          entityAdjustment = systemResponse.performedAdjustment.rootDirectoryAdjustment,
          pathPrefix = "",
          openedFilePaths = openedFilePaths,
          expandedDirectoryPaths = expandedDirectoryPaths,
      )

      val blocks = buildList {
        if (expandedDirectoryPaths.isNotEmpty()) {
          add(MdBlock.Paragraph.of("I expanded these directories:"))
          add(
              MdBlock.ListBlock.of(
                  items = expandedDirectoryPaths.map { path -> pathListItem(path = path) }
              )
          )
        }

        if (openedFilePaths.isNotEmpty()) {
          add(MdBlock.Paragraph.of("I opened these files:"))
          add(
              MdBlock.ListBlock.of(
                  items = openedFilePaths.map { path -> pathListItem(path = path) }
              )
          )
        }

        add(
            MdBlock.Paragraph.of(
                inlineNodes =
                    listOf(
                        MdInlineNode.Text("The freshly opened files are visible "),
                        MdInlineNode.Emphasis.of("above"),
                        MdInlineNode.Text(
                            " this message, in the Worktree section, with timestamp [t = ${systemResponse.adjustmentTimestamp.t}].",
                        ),
                    ),
            ),
        )
        add(
            MdBlock.Paragraph.of(
                "If the opened files revealed some new information or references relevant to The Task, we can go on.",
            ),
        )
        add(
            MdBlock.Paragraph.of(
                "Should I expand any directories or open any more files, or do you consider scouting finished?"
            ),
        )
        add(
            MdBlock.Paragraph.of(
                inlineNodes =
                    listOf(
                        MdInlineNode.Strong.of("NOTE:"),
                        MdInlineNode.Text(" It's not time to start completing The Task yet"),
                    ),
            ),
        )
      }

      return MdDocument(
          rootChapter =
              MdChapter.leaf(
                  title = MdInlineContent.of("Scouting round"),
                  element = MdElement(blocks = blocks),
              ),
      )
    }

    private fun pathListItem(
        path: String,
    ): MdBlock.ListBlock.Item =
        MdBlock.ListBlock.Item(
            content = MdInlineContent(inlineNodes = listOf(MdInlineNode.Code(path))),
            nestedLevel = null,
        )

    private fun collectAdjustmentPaths(
        entityAdjustment: VedEntityAdjustment,
        pathPrefix: String,
        openedFilePaths: MutableList<String>,
        expandedDirectoryPaths: MutableList<String>,
    ) {
      when (entityAdjustment) {
        VedFileAdjustment.Open -> openedFilePaths += pathPrefix

        // Exposure toggles (leader/assistant engine only) open no files and expand no directories,
        // so they contribute no scouting-round paths. The classic engine never emits them.
        VedFileAdjustment.Expose,
        VedFileAdjustment.Hide -> Unit

        VedDirectoryAdjustment.Expand -> expandedDirectoryPaths += pathPrefix

        is VedDirectoryAdjustment.Dive ->
            entityAdjustment.childAdjustmentByName.entries
                .sortedBy { (name, _) -> name.content }
                .forEach { (name, childAdjustment) ->
                  collectAdjustmentPaths(
                      entityAdjustment = childAdjustment,
                      pathPrefix = "$pathPrefix/${name.content}",
                      openedFilePaths = openedFilePaths,
                      expandedDirectoryPaths = expandedDirectoryPaths,
                  )
                }
      }
    }

    // endregion

    // region Workspace brief prompts

    private val workspaceBriefIntroText =
        """
        I'll now consult another expert AI system, which will prepare the implementation plan.

        The expert AI system will be provided with The Task description, but will NOT see the full workspace.

        Be the expert's eyes. Describe the workspace's content in the context of The Task.

        Present the important parts of the files as code snippets. In some cases, including the full file content might be appropriate.

        Summarize the parts that are too lengthy or repetitive to include them directly, yet still relevant.

        **IMPORTANT:** Restrain from _completing_ The Task and including a partial/full solution in your response. Do not include instructions or requirements.
        """
            .trimIndent()

    // endregion

    // region Solution implementation prompts

    private const val implementationPlanFramingText =
        "An expert AI system prepared this Implementation Plan:"

    private val implementationPhaseIntroText =
        """
        We're now ready to start editing the files, according to the Implementation Plan. I have direct worktree access.

        Which files should I edit and how, specifically?

        Start your response with a `# Patch` heading.

        When referring the files, mention their absolute path, as presented in the Workspace. Use inline code for paths.

        **NOTE:** I'll assume that all inline code nodes (e.g. `/foo/bar/baz`) starting with `/` are paths.

        For created or replaced fragments, provide code snippets with the literal new content.
        """
            .trimIndent()

    // endregion

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
                text = systemIntroText,
            ),
            OaiMessage(
                role = OaiRole.User,
                text = taskDescription.body.render(),
                name = simpleAiName,
            ),
            OaiMessage(
                role = OaiRole.System,
                text = editorWorktree.renderFiles().render(),
            ),
        )

    private fun renderPatchSystemResponse(
        systemResponse: PatchMessage.SystemResponse,
    ): MdChapter {
      val approvalTimestamp = systemResponse.patchTimestamp
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
                              "I patched the files according to your request.",
                          ),
                          MdBlock.Paragraph.of("Timestamp: [t = ${approvalTimestamp.t}]"),
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
                                      MdBlock.Paragraph.of(
                                          "I've run checks after editing the files. Some issues were found."
                                      ),
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
                                                    modulePath.toUnixAbsolutePathString(),
                                                ),
                                                MdInlineNode.Text(":"),
                                            ),
                                    ),
                                element =
                                    MdElement(
                                        blocks =
                                            listOf(
                                                MdBlock.CodeBlock(
                                                    code = moduleFailure.diagnosticOutput,
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

  override suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      scoutingObserver: HrsTaskCompleter.ScoutingObserver,
  ): ScoutMessage {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.User,
                        text = scoutingIntroText,
                        name = simpleAiName,
                    ),
                ) +
                    scoutingLog.logEntries.flatMap { logEntry ->
                      listOf(
                          OaiMessage(
                              role = OaiRole.Assistant,
                              text = logEntry.scoutMessage.body,
                          ),
                          OaiMessage(
                              role = OaiRole.User,
                              text =
                                  renderScoutingFollowupDocument(
                                          systemResponse = logEntry.systemResponse,
                                      )
                                      .render(),
                          ),
                      )
                    },
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    scoutingObserver.observeRawResponse(response = response)

    return ScoutMessage(
        body = response.responseText,
    )
  }

  override suspend fun prepareWorkspaceBrief(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      workspaceBriefingObserver: HrsTaskCompleter.WorkspaceBriefingObserver,
  ): HrsExpertAiSystem.WorkspaceBrief {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.User,
                        text = workspaceBriefIntroText,
                    ),
                ),
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    workspaceBriefingObserver.observeRawResponse(response = response)

    return HrsExpertAiSystem.WorkspaceBrief(
        body = response.responseText,
    )
  }

  override suspend fun implementSolution(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      implementationPlan: ImplementationPlan,
      solutionImplementationLog: SolutionImplementationLog,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ): PatchMessage {
    val request =
        renderRequest(
            taskDescription = taskDescription,
            editorWorktree = editorWorktree,
            tailMessages =
                listOf(
                    OaiMessage(
                        role = OaiRole.User,
                        text = implementationPlanFramingText,
                        name = simpleAiName,
                    ),
                    OaiMessage(
                        role = OaiRole.User,
                        text = implementationPlan.body,
                        name = simpleAiName,
                    ),
                    OaiMessage(
                        role = OaiRole.User,
                        text = implementationPhaseIntroText,
                        name = simpleAiName,
                    ),
                ) +
                    solutionImplementationLog.logEntries.flatMap { logEntry ->
                      listOf(
                          OaiMessage(
                              role = OaiRole.Assistant,
                              text = logEntry.patchMessage.body,
                          ),
                          OaiMessage(
                              role = OaiRole.User,
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
                                  "Let's fix the issues found above. Which edits should I make? Start your response with a `# Patch` heading.",
                              name = simpleAiName,
                          ),
                      )
                    },
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    solutionImplementationObserver.observeRawResponse(response = response)

    return PatchMessage(
        body = response.responseText,
    )
  }
}

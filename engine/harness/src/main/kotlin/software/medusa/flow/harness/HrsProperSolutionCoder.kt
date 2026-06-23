package software.medusa.flow.harness

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
import software.medusa.commons.openai_client.createStructuredCompletion
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.render
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

class HrsProperSolutionCoder(
    private val openaiClient: OaiConfiguredClient,
) : HrsSolutionCoder {
  companion object {
    private val introductionText =
        """
        You are an autonomous coding agent. You are given a software task and a worktree: a
        directory tree together with the full content of the files that are currently opened. Edit
        the opened files so that the task is solved, and return the full new content of every file
        you change. Only already-opened files may be edited.
        """
            .trimIndent()
  }

  override suspend fun codeSolution(
      editorWorktree: VedWorktree,
      taskDescription: HrsTaskDescription,
  ): VedWorktreePatch {
    val worktreeChapter = editorWorktree.render()

    val promptText =
        MdDocument(
                rootChapter =
                    MdChapter.wrapper(
                        title = textTitle("Agentic task"),
                        subChapters =
                            listOf(
                                MdChapter.leaf(
                                    title = textTitle("Introduction"),
                                    element = paragraph(introductionText),
                                ),
                                MdChapter.leaf(
                                    title = textTitle("Task description"),
                                    element = taskDescription.body,
                                ),
                                worktreeChapter,
                            ),
                    ),
            )
            .render()

    val response =
        openaiClient.createStructuredCompletion(
            request =
                OaiConfiguredClient.CompletionRequest(
                    input =
                        OaiChat(
                            messages =
                                listOf(
                                    OaiMessage(
                                        role = OaiRole.System,
                                        text = promptText,
                                    ),
                                ),
                        ),
                    reasoningEffort = OaiConfiguredClient.ReasoningEffort.High,
                ),
            responseSerializer = HrsRawWorktreePatch.serializer(),
        )

    val rawWorktreePatch = response.responseObject

    return rawWorktreePatch.toFullWorktreePatch(baseWorktree = editorWorktree)
  }
}

private fun textTitle(
    text: String,
): MdInlineContent = MdInlineContent(listOf(MdInlineNode.Text(text)))

private fun paragraph(
    text: String,
): MdElement = MdElement(blocks = listOf(MdBlock.Paragraph(content = textTitle(text))))

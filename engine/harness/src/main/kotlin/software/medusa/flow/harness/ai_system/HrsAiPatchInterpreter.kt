package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
import software.medusa.commons.openai_client.createStructuredCompletion
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * An [HrsPatchInterpreter] backed by a structured-output model call.
 *
 * It pulls the paths the message points at (inline-code tokens starting with `/`, as the frontline
 * was told), takes the *current* content of those opened files from [editorWorktree], and asks the
 * model to apply the message's described edits and return each changed file's full new content as a
 * [HrsRawWorktreePatch]. The whole-file result is then rebased onto the worktree.
 */
class HrsAiPatchInterpreter(
    private val openaiClient: OaiConfiguredClient,
) : HrsPatchInterpreter {
  private companion object {
    private val systemPromptText =
        """
        You turn an engineer's edit description into concrete file operations.

        You are given the description and the current content of the files it refers to. Sort each affected file into exactly one of: edited (an existing file whose full new content you return, not a diff), created (a brand-new file whose full content you return), or deleted (a file to remove). Preserve everything the description does not change, and use each file's absolute path exactly as shown.

        Be strict: only edit files that already exist, only create files that do not exist yet, and only delete files that exist. Leave files the description does not touch out entirely.
        """
            .trimIndent()
  }

  override suspend fun interpretPatch(
      patchMessage: PatchMessage,
      editorWorktree: VedWorktree,
  ): VedWorktreePatch {
    val request =
        OaiConfiguredClient.CompletionRequest(
            input =
                OaiChat(
                    messages =
                        listOf(
                            OaiMessage(role = OaiRole.System, text = systemPromptText),
                            OaiMessage(role = OaiRole.User, text = patchMessage.body),
                            OaiMessage(
                                role = OaiRole.System,
                                text =
                                    renderReferencedFiles(
                                        patchMessage = patchMessage,
                                        editorWorktree = editorWorktree,
                                    ),
                            ),
                        ),
                ),
            reasoningEffort = OaiConfiguredClient.ReasoningEffort.Low,
        )

    val response =
        openaiClient.createStructuredCompletion(
            request = request,
            responseSchemaName = "worktree_patch",
            responseSerializer = HrsRawWorktreePatch.serializer(),
        )

    return response.responseObject.toFullWorktreePatch(baseWorktree = editorWorktree)
  }

  /**
   * Renders the current content of the files the message points at — every opened file whose path
   * appears as inline code in the message, as the frontline was told (`/foo/bar` inline code is a
   * path). Falls back to every open file when the message names none we recognise (or does not
   * parse as Markdown), so the model is never left without context.
   */
  private fun renderReferencedFiles(
      patchMessage: PatchMessage,
      editorWorktree: VedWorktree,
  ): String {
    val contentByPath =
        editorWorktree.visitOpenedFiles().associate { visitedFile ->
          visitedFile.filePath.toUnixAbsolutePathString() to visitedFile.openedFile.currentContent
        }

    val referencedPaths =
        runCatching { MdDocument.parse(markdownSource = patchMessage.body) }
            .getOrNull()
            ?.visitInlineNodes()
            ?.filterIsInstance<MdInlineNode.Code>()
            ?.map { codeNode -> codeNode.code }
            ?.filter { path -> path in contentByPath }
            ?.distinct()
            ?.toList()
            .orEmpty()

    val selectedPaths = referencedPaths.ifEmpty { contentByPath.keys.toList() }

    return buildString {
      appendLine("Current content of the referenced files:")

      selectedPaths.forEach { path ->
        val content = contentByPath.getValue(path)

        appendLine()
        appendLine("`$path`:")
        appendLine()
        appendLine("```")
        append(content.dump())
        appendLine("```")
      }
    }
  }
}

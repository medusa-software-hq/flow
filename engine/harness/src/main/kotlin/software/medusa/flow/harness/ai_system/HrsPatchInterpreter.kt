package software.medusa.flow.harness.ai_system

import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * Interprets the frontline's free-form [PatchMessage] into a structured [VedWorktreePatch].
 *
 * The message describes edits in natural language (with new snippets), so the interpreter is given
 * [editorWorktree] as well — it takes the *old* content of the files the message points at and
 * reconstructs their full new content. Like [HrsScoutDecisionInterpreter], the message is only
 * interpreted here, when the driver actually needs to apply the edits.
 */
interface HrsPatchInterpreter {
  suspend fun interpretPatch(
      patchMessage: PatchMessage,
      editorWorktree: VedWorktree,
  ): VedWorktreePatch
}

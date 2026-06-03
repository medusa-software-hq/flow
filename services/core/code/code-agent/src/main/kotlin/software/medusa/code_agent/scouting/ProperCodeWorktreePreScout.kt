package software.medusa.code_agent.scouting

import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.LockState
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.OpenedCodeFile
import software.medusa.code_agent.virtual_workspace.document.CodeDocument
import software.medusa.code_agent.virtual_workspace.document.CodeDocumentBootstrapper
import software.medusa.code_agent.virtual_workspace.document.CodeLanguage
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.commons.filesystem.tech.readTechContent
import software.medusa.commons.paths.UnixPath
import software.medusa.git.worktree.GitExcludedWorktreeDirectory
import software.medusa.git.worktree.GitIncludedWorktreeDirectory
import software.medusa.git.worktree.GitWorktree
import software.medusa.git.worktree.GitWorktreeDirectory
import software.medusa.git.worktree.GitWorktreeEntity
import software.medusa.git.worktree.GitWorktreeFile
import software.medusa.git.worktree.GitWorktreeFilter

class ProperCodeWorktreePreScout(
    private val documentBootstrapper: CodeDocumentBootstrapper,
) : CodeWorktreePreScout {
  override suspend fun preScoutWorktree(
      gitWorktree: GitWorktree,
  ): CodeVirtualWorkspace =
      CodeVirtualWorkspace(
          rootDirectory =
              with(
                  ScoutingContext(
                      documentBootstrapper = documentBootstrapper,
                  ),
              ) {
                gitWorktree.rootDirectory.scoutIncludedDirectory()
              },
      )
}

private data class ScoutingContext(
    val documentBootstrapper: CodeDocumentBootstrapper,
)

context(scoutingContext: ScoutingContext)
private suspend fun GitWorktreeEntity.scoutEntity(
    name: UnixPath.Name.Literal,
): CodeVirtualWorkspace.Entity =
    when (this) {
      is GitWorktreeDirectory -> scoutDirectory()
      is GitWorktreeFile -> scoutFile(fileName = name)
    }

context(scoutingContext: ScoutingContext)
private suspend fun GitWorktreeDirectory.scoutDirectory(): CodeVirtualWorkspace.Directory =
    when (this) {
      is GitIncludedWorktreeDirectory -> scoutIncludedDirectory()

      is GitExcludedWorktreeDirectory ->
          CodeVirtualWorkspace.CollapsedDirectory(
              vcsStatus = status.toVcsStatus(),
              lockState = LockState.Unlocked,
          )
    }

context(scoutingContext: ScoutingContext)
private suspend fun GitIncludedWorktreeDirectory.scoutIncludedDirectory():
    CodeVirtualWorkspace.ExpandedDirectory {
  val children = readStructure().mapValues { (name, child) -> child.scoutEntity(name = name) }

  return CodeVirtualWorkspace.ExpandedDirectory(
      childEntityByName = children,
  )
}

context(scoutingContext: ScoutingContext)
private suspend fun GitWorktreeFile.scoutFile(
    fileName: UnixPath.Name.Literal,
): CodeVirtualWorkspace.File =
    when (val techContent = asFsEntity.readTechContent()) {
      TechFileContent.Binary -> {
        CodeVirtualWorkspace.ClosedFile(
            vcsStatus = status.toVcsStatus(),
            lockState = LockState.Locked,
        )
      }

      is TechFileContent.Code -> {
        val loadedDocument =
            CodeDocument.load(
                language = CodeLanguage.Kotlin, // TODO: Detect language heuristically (based on
                // filename/extension)
                content = techContent,
            )

        val bootstrappedDocument =
            scoutingContext.documentBootstrapper.bootstrapDocument(
                document = loadedDocument,
            )

        OpenedCodeFile(
            vcsStatus = status.toVcsStatus(),
            document = bootstrappedDocument,
        )
      }
    }

private fun GitWorktreeEntity.Status.toVcsStatus(): CodeVirtualWorkspace.VcsStatus =
    when (this) {
      is GitWorktreeEntity.Status.Considered ->
          when (classification) {
            GitWorktreeFilter.Classification.Include -> CodeVirtualWorkspace.VcsStatus.Included
            GitWorktreeFilter.Classification.Ignore -> CodeVirtualWorkspace.VcsStatus.Ignored
          }

      GitWorktreeEntity.Status.NonConsidered -> CodeVirtualWorkspace.VcsStatus.NonConsidered
    }

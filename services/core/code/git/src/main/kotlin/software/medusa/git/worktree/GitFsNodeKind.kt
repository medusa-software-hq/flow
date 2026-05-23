package software.medusa.git.worktree

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile

enum class GitFsNodeKind {
  Directory,
  File,
}

val ReadonlyCompatFsEntity.fsNodeKind: GitFsNodeKind
  get() =
      when (this) {
        is ReadonlyCompatFsDirectory -> GitFsNodeKind.Directory
        is ReadonlyCompatFsFile -> GitFsNodeKind.File
      }

package software.medusa.git

import java.nio.file.Path

interface GitEngine {
  fun openRepository(repoPath: Path): GitEngineRepository
}

interface GitEngineRepository {
  val path: Path

  fun commit(
      message: String,
  ): GitCommitResult
}

data class GitCommitResult(
    val commitHash: String,
)

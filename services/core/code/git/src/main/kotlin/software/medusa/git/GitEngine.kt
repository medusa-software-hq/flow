package software.medusa.git

import java.nio.file.Path

interface GitEngine {
  fun openRepository(repoPath: Path): GitRepository
}

interface GitRepository {
  fun commit(request: GitCommitRequest): GitCommitResult
}

data class GitCommitRequest(
    val message: String,
    val pathspecs: List<String> = listOf("."),
)

data class GitCommitResult(
    val commitHash: String,
)

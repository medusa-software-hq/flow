package software.medusa.git

sealed class GitCommitResolver {
  internal abstract fun resolveTree(): GitCommitHash
}

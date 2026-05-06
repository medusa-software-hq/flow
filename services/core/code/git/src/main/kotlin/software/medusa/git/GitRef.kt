package software.medusa.git

@JvmInline
value class GitRefPath(
    val segments: List<String>,
) {
  companion object {
    fun of(
        vararg segments: String,
    ): GitRefPath = GitRefPath(segments.toList())
  }

  fun resolve(
      innerPath: GitRefPath,
  ): GitRefPath = GitRefPath(segments + innerPath.segments)
}

@JvmInline
value class GitRef(
    val path: GitRefPath,
)

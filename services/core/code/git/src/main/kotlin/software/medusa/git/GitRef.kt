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

  init {
    require(segments.isNotEmpty() && segments.none { it.isBlank() || it.contains("/") }) {
      "Git ref segments must be non-empty and cannot contain slashes"
    }
  }

  fun toRefString(): String = segments.joinToString("/")

  fun resolve(
      innerPath: GitRefPath,
  ): GitRefPath = GitRefPath(segments + innerPath.segments)
}

@JvmInline
value class GitRef(
    val path: GitRefPath,
)

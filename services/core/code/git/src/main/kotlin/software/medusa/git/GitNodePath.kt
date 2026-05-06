package software.medusa.git

sealed class GitNodePath {
  abstract val path: List<String>

  abstract fun toDebugString(): String
}

data class GitLeafPath(
    val treePath: GitTreePath,
    val leafName: String,
) : GitNodePath() {
  override val path: List<String>
    get() = treePath.path + listOf(leafName)

  override fun toDebugString(): String = "leaf:${treePath.toDebugStringPart()}/$leafName}"
}

data class GitTreePath(override val path: List<String>) : GitNodePath() {
  companion object {
    val Root: GitTreePath = GitTreePath(path = emptyList())
  }

  fun file(
      name: String,
  ): GitLeafPath =
      GitLeafPath(
          treePath = this,
          leafName = name,
      )

  fun subTree(
      name: String,
  ): GitTreePath =
      GitTreePath(
          path = path + listOf(name),
      )

  internal fun toDebugStringPart(): String = "/${path.joinToString(separator = "/")}"

  override fun toDebugString(): String = "tree:${toDebugStringPart()}"
}

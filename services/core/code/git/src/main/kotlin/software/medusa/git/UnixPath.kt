package software.medusa.git

import java.nio.file.Path

sealed interface UnixPath {
  @JvmInline
  value class Absolute
  internal constructor(
      val rootRelative: Relative,
  ) : UnixPath {
    companion object {
      fun of(vararg pathSegments: String): Absolute =
          Absolute(
              rootRelative = Relative.of(*pathSegments),
          )
    }

    override fun toUnixPathString(): String = "/${rootRelative.toUnixPathString()}"

    override fun toPath(): Path = Path.of("/", *rootRelative.path.toTypedArray())
  }

  @JvmInline
  value class Relative
  private constructor(
      /**
       * Path segments, in order from root to leaf. May contain segments with special
       * interpretations: '.' (current directory) and '..' (parent directory). Segments cannot be
       * empty or contain '/'.
       */
      val path: List<String>,
  ) : UnixPath {
    companion object {
      fun of(vararg pathSegments: String) =
          Relative(
              path = pathSegments.toList(),
          )

      fun parse(
          pathText: String,
      ): Relative {
        require(pathText.isNotEmpty() && !pathText.startsWith(Separator))

        return Relative(
            path = pathText.split(Separator),
        )
      }
    }

    init {
      require(path.isNotEmpty() && path.none { it.isEmpty() || it.contains(Separator) }) {
        "Pathological Unix path: $path"
      }
    }

    override fun toUnixPathString(): String = path.joinToString(SeparatorString)

    override fun toPath(): Path = Path.of(".", *path.toTypedArray())

    fun prepend(
        directoryName: String,
    ): Relative =
        Relative(
            path = listOf(directoryName) + path,
        )
  }

  companion object {
    const val Separator = '/'
    const val SeparatorString = Separator.toString()

    const val Dot = "."
    const val DotDot = ".."

    /** Parse a Unix-like path text. */
    fun parse(
        pathText: String,
    ): UnixPath =
        when {
          pathText.startsWith(Separator) ->
              Absolute(
                  rootRelative = Relative.parse(pathText.drop(1)),
              )

          else -> Relative.parse(pathText)
        }
  }

  fun toUnixPathString(): String

  // It likely works only on *nix systems
  fun toPath(): Path
}

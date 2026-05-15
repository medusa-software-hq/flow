package software.medusa.commons.paths

/**
 * A simplified model of a Unix-style path.
 *
 * A path is a sequence of _names_ (path components). Each name refers to a file that is a direct
 * child of the directory referred to by the previous name. A file may be either a regular file or a
 * directory.
 *
 * Paths are explicitly represented as either _absolute_ or _relative_:
 * - an absolute path is interpreted relative to the root directory
 * - a relative path is interpreted relative to some other path or filesystem context
 *
 * Names may be either _literal_ or _symbolic_. Symbolic names represent the conventional Unix
 * special components "." (current directory) and ".." (parent directory).
 *
 * This model represents Unix-style path syntax, not full filesystem path-resolution semantics. In
 * particular:
 * - POSIX special paths beginning with "//" are unrepresentable
 * - filesystem-specific behavior, such as symbolic-link resolution, is not modeled
 * - arbitrary binary file names are unrepresentable because names are stored as [String]
 *
 * See the design documentation for background, rationale, and edge-case discussion.
 *
 * @param NameT Type of the names comprising the path.
 */
sealed class UnixPath<out NameT : UnixPath.Name> {
  companion object {
    const val Separator = '/'
  }

  /** A name within a Unix-style path. */
  sealed interface Name {
    companion object {
      /**
       * Parses a name string into a [Name] instance. If the name string is "." or "..", it will be
       * parsed as a symbolic name, otherwise as a literal name.
       *
       * @throws IllegalArgumentException if the name string is empty or contains the separator
       *   character.
       */
      internal fun parse(nameString: String): Name =
          when (nameString) {
            Symbolic.ThisDirectory.name -> Symbolic.ThisDirectory
            Symbolic.ParentDirectory.name -> Symbolic.ParentDirectory
            else -> Literal(name = nameString)
          }
    }

    /**
     * A literal name, corresponding to a (possibly existing) file on the filesystem. Arbitrary
     * non-Unicode (binary) names are unrepresentable.
     */
    @JvmInline
    value class Literal(
        override val name: String,
    ) : Name {
      init {
        require(name.isNotEmpty()) { "Name cannot be empty" }

        require(!name.contains(Separator)) { "Name cannot contain '$Separator' character" }

        require(name != Symbolic.ThisDirectory.name && name != Symbolic.ParentDirectory.name) {
          "Name cannot be ${Symbolic.ThisDirectory.name} or ${Symbolic.ParentDirectory.name}"
        }
      }
    }

    /** A symbolic name, having a special meaning. */
    sealed interface Symbolic : Name {
      /** The symbolic name "." (dot) refers to the current directory. */
      data object ThisDirectory : Symbolic {
        override val name: String = "."
      }

      /** The symbolic name ".." (dot-dot) refers to the parent directory. */
      data object ParentDirectory : Symbolic {
        override val name: String = ".."
      }
    }

    val name: String
  }
}

/** Relative Unix-like path. */
data class RelativeUnixPath<out NameT : UnixPath.Name>(
    val names: List<NameT>,
) : UnixPath<NameT>() {
  companion object {
    /**
     * An empty relative path. Semantically, an empty relative path is equivalent to a series of
     * [Name.Symbolic.ThisDirectory] names (".", "./.", "././.", etc.).
     */
    val Empty =
        RelativeUnixPath(
            names = emptyList(),
        )

    fun <NameT : UnixPath.Name> of(
        vararg names: NameT,
    ): RelativeUnixPath<NameT> = RelativeUnixPath(names = names.toList())

    fun <NameT : UnixPath.Name> of(
        names: List<NameT>,
    ): RelativeUnixPath<NameT> = RelativeUnixPath(names = names)

    /**
     * Parses a conventional relative Unix-style path string into a [RelativeUnixPath] instance.
     *
     * @throws IllegalArgumentException if the path string is empty, starts with the separator
     *   character or contains consecutive slashes.
     */
    fun parse(
        unixPathString: String,
    ): RelativeUnixPath<*> {
      val firstChar =
          unixPathString.firstOrNull()
              ?: throw IllegalArgumentException("Path string cannot be empty")

      require(firstChar != Separator) {
        "Relative path string cannot start with '$Separator' character"
      }

      // Attempt to parse the path string. If it contains consecutive separators, some of the
      // resulting names strings will be empty.
      return RelativeUnixPath(
          names = unixPathString.split(Separator).map { Name.parse(it) },
      )
    }

    fun <NameT : UnixPath.Name> concat(
        vararg paths: RelativeUnixPath<NameT>,
    ): RelativeUnixPath<NameT> = concat(paths.toList())

    fun <NameT : UnixPath.Name> concat(
        paths: List<RelativeUnixPath<NameT>>,
    ): RelativeUnixPath<NameT> =
        RelativeUnixPath(
            names = paths.flatMap { it.names },
        )
  }

  /** Builds a conventional Unix-style relative path string. */
  fun toUnixRelativePathString(): String = names.joinToString(separator = "$Separator") { it.name }
}

typealias LiteralRelativeUnixPath = RelativeUnixPath<UnixPath.Name.Literal>

fun RelativeUnixPath<*>.toLiteral(): LiteralRelativeUnixPath? {
  val literalNames = names.map { it as? UnixPath.Name.Literal }.allNonNullOrNull() ?: return null

  return LiteralRelativeUnixPath(names = literalNames)
}

/** Absolute Unix-like path. */
data class AbsoluteUnixPath<out NameT : UnixPath.Name>(
    /** Path relative to the root directory. */
    val innerPath: RelativeUnixPath<NameT>,
) : UnixPath<NameT>() {
  companion object {
    /** Path to the root directory ("/"). */
    val Root =
        AbsoluteUnixPath(
            innerPath = RelativeUnixPath.Empty,
        )

    fun <NameT : Name> of(
        vararg names: NameT,
    ): AbsoluteUnixPath<NameT> =
        AbsoluteUnixPath(
            innerPath = RelativeUnixPath(names = names.toList()),
        )

    fun <NameT : Name> of(
        names: List<NameT>,
    ): AbsoluteUnixPath<NameT> =
        AbsoluteUnixPath(
            innerPath = RelativeUnixPath(names = names),
        )

    /**
     * Parses a conventional absolute Unix-style path string into an [AbsoluteUnixPath] instance.
     *
     * @throws IllegalArgumentException if the path string is empty, doesn't start with the
     *   separator character or contains consecutive slashes.
     */
    fun parse(unixPathString: String): AbsoluteUnixPath<*> {
      require(unixPathString.isNotEmpty()) { "Path string cannot be empty" }

      require(unixPathString.first() == Separator) {
        "Absolute path string must start with '$Separator' character"
      }

      return when (unixPathString.length) {
        1 -> Root

        else ->
            AbsoluteUnixPath(
                innerPath = RelativeUnixPath.parse(unixPathString.drop(1)),
            )
      }
    }
  }

  /** Builds a conventional Unix-style absolute path string. */
  fun toUnixAbsolutePathString(): String = "$Separator${innerPath.toUnixRelativePathString()}"
}

/** Resolves a nested relative path against this absolute path, returning a new absolute path. */
fun <NameT : UnixPath.Name> AbsoluteUnixPath<NameT>.resolve(
    nestedPath: RelativeUnixPath<NameT>,
): AbsoluteUnixPath<NameT> =
    AbsoluteUnixPath(
        innerPath = RelativeUnixPath.concat(innerPath, nestedPath),
    )

typealias LiteralAbsoluteUnixPath = AbsoluteUnixPath<UnixPath.Name.Literal>

fun AbsoluteUnixPath<*>.toLiteral(): LiteralAbsoluteUnixPath? {
  val literalInnerPath = innerPath.toLiteral() ?: return null

  return LiteralAbsoluteUnixPath(innerPath = literalInnerPath)
}

private fun <T : Any> List<T?>.allNonNullOrNull(): List<T>? =
    when {
      all { it != null } -> @Suppress("UNCHECKED_CAST") (this as List<T>)
      else -> null
    }

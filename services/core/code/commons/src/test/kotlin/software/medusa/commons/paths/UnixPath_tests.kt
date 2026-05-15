package software.medusa.commons.paths

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import org.junit.jupiter.api.assertThrows
import software.medusa.commons.paths.UnixPath.Name

class UnixPath_tests {
  @Test
  fun test_RelativeUnixPath_construct_empty() {
    assertEquals(
        expected = RelativeUnixPath.Empty,
        actual =
            RelativeUnixPath(
                names = emptyList(),
            ),
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_emptyName() {
    assertIs<IllegalArgumentException>(
        assertThrows {
          RelativeUnixPath(
              names =
                  listOf(
                      Name.Literal(""),
                  ),
          )
        },
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_nameContainingSeparator() {
    assertIs<IllegalArgumentException>(
        assertThrows {
          RelativeUnixPath(
              names =
                  listOf(
                      Name.Literal("files"),
                      Name.Literal("n/a.txt"),
                  ),
          )
        },
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_symbolicName_dot() {
    assertIs<IllegalArgumentException>(
        assertThrows {
          RelativeUnixPath(
              names =
                  listOf(
                      Name.Literal("characters"),
                      Name.Literal("."),
                  ),
          )
        },
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_symbolicName_dotDot() {
    assertIs<IllegalArgumentException>(
        assertThrows {
          RelativeUnixPath(
              names =
                  listOf(
                      Name.Literal("double-characters"),
                      Name.Literal(".."),
                  ),
          )
        },
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_literalName_tripleDot() {
    val unixPath =
        RelativeUnixPath(
            names =
                listOf(
                    Name.Literal("triple-characters"),
                    Name.Literal("..."),
                ),
        )

    assertEquals(
        expected =
            listOf(
                Name.Literal("triple-characters"),
                Name.Literal("..."),
            ),
        actual = unixPath.names,
    )
  }

  @Test
  fun test_RelativeUnixPath_construct_literalName_hiddenFile() {
    val unixPath =
        RelativeUnixPath(
            names =
                listOf(
                    Name.Literal("code"),
                    Name.Literal(".gitignore"),
                ),
        )

    assertEquals(
        expected =
            listOf(
                Name.Literal("code"),
                Name.Literal(".gitignore"),
            ),
        actual = unixPath.names,
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_empty() {
    assertIs<IllegalArgumentException>(
        assertFails { RelativeUnixPath.parse("") },
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_empty() {
    assertEquals(
        expected = "",
        actual = RelativeUnixPath.Empty.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_topLevel() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("dist")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("dist"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_leadingSeparator() {
    assertIs<IllegalArgumentException>(
        assertFails { RelativeUnixPath.parse("/tmp") },
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_topLevel() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Literal("dist"),
        )

    assertEquals(
        expected = "dist",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_trailingSeparator() {
    assertIs<IllegalArgumentException>(
        assertFails { RelativeUnixPath.parse("tmp/") },
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_nested() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("build/a.out")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("build"),
                Name.Literal("a.out"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_nested() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Literal("build"),
            Name.Literal("a.out"),
        )

    assertEquals(
        expected = "build/a.out",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_deeplyNested() {
    val parsedPath: RelativeUnixPath<Name> =
        RelativeUnixPath.parse("archive/backups/2026/01/01/backup.tar.gz")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("archive"),
                Name.Literal("backups"),
                Name.Literal("2026"),
                Name.Literal("01"),
                Name.Literal("01"),
                Name.Literal("backup.tar.gz"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_deeplyNested() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Literal("archive"),
            Name.Literal("backups"),
            Name.Literal("2026"),
            Name.Literal("01"),
            Name.Literal("01"),
            Name.Literal("backup.tar.gz"),
        )

    assertEquals(
        expected = "archive/backups/2026/01/01/backup.tar.gz",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_consecutive_separator() {
    assertIs<IllegalArgumentException>(
        assertFails { RelativeUnixPath.parse("dist/bundles//bundle.js") },
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dot() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse(".")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Symbolic.ThisDirectory,
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dot() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Symbolic.ThisDirectory,
        )

    assertEquals(
        expected = ".",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dot_nested() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("./dist/bundle.js")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Symbolic.ThisDirectory,
                Name.Literal("dist"),
                Name.Literal("bundle.js"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dot_nested() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Symbolic.ThisDirectory,
            Name.Literal("dist"),
            Name.Literal("bundle.js"),
        )

    assertEquals(
        expected = "./dist/bundle.js",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dot_inner() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("dist/./bundle.js")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("dist"),
                Name.Symbolic.ThisDirectory,
                Name.Literal("bundle.js"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dot_inner() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Literal("dist"),
            Name.Symbolic.ThisDirectory,
            Name.Literal("bundle.js"),
        )

    assertEquals(
        expected = "dist/./bundle.js",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dotDot() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("..")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Symbolic.ParentDirectory,
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dotDot() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Symbolic.ParentDirectory,
        )

    assertEquals(
        expected = "..",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dotDot_nested() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("../dist/bundle.js")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Symbolic.ParentDirectory,
                Name.Literal("dist"),
                Name.Literal("bundle.js"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dotDot_nested() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Symbolic.ParentDirectory,
            Name.Literal("dist"),
            Name.Literal("bundle.js"),
        )

    assertEquals(
        expected = "../dist/bundle.js",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dotDot_repeated_nested() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("../../../dist/bundle.js")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Symbolic.ParentDirectory,
                Name.Symbolic.ParentDirectory,
                Name.Symbolic.ParentDirectory,
                Name.Literal("dist"),
                Name.Literal("bundle.js"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dotDot_repeated_nested() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Symbolic.ParentDirectory,
            Name.Symbolic.ParentDirectory,
            Name.Symbolic.ParentDirectory,
            Name.Literal("dist"),
            Name.Literal("bundle.js"),
        )

    assertEquals(
        expected = "../../../dist/bundle.js",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_parse_dotDot_inner() {
    val parsedPath: RelativeUnixPath<Name> = RelativeUnixPath.parse("dist/../bundle.js")

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("dist"),
                Name.Symbolic.ParentDirectory,
                Name.Literal("bundle.js"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_toUnixRelativePathString_dotDot_inner() {
    val unixPath =
        RelativeUnixPath.of(
            Name.Literal("dist"),
            Name.Symbolic.ParentDirectory,
            Name.Literal("bundle.js"),
        )

    assertEquals(
        expected = "dist/../bundle.js",
        actual = unixPath.toUnixRelativePathString(),
    )
  }

  @Test
  fun test_RelativeUnixPath_concat_bothEmpty() {
    val concatenatedPath =
        RelativeUnixPath.concat(
            RelativeUnixPath.Empty,
            RelativeUnixPath.Empty,
        )

    assertEquals(
        expected = RelativeUnixPath.Empty,
        actual = concatenatedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_concat_firstEmpty() {
    val properPath =
        RelativeUnixPath.of(
            Name.Literal("dist"),
        )

    val concatenatedPath =
        RelativeUnixPath.concat(
            RelativeUnixPath.Empty,
            properPath,
        )

    assertEquals(
        expected = properPath,
        actual = concatenatedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_concat_secondEmpty() {
    val properPath =
        RelativeUnixPath.of(
            Name.Literal("dist"),
        )

    val concatenatedPath =
        RelativeUnixPath.concat(
            properPath,
            RelativeUnixPath.Empty,
        )

    assertEquals(
        expected = properPath,
        actual = concatenatedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_concat_bothProper() {
    val concatenatedPath =
        RelativeUnixPath.concat(
            RelativeUnixPath.of(
                Name.Literal("build"),
            ),
            RelativeUnixPath.of(
                Name.Literal("a.out"),
            ),
        )

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("build"),
                Name.Literal("a.out"),
            ),
        actual = concatenatedPath,
    )
  }

  @Test
  fun test_RelativeUnixPath_concat_multipleProper() {
    val concatenatedPath =
        RelativeUnixPath.concat(
            RelativeUnixPath.of(
                Name.Literal("build"),
            ),
            RelativeUnixPath.of(
                Name.Literal("artifacts"),
                Name.Literal("intermediate"),
            ),
            RelativeUnixPath.of(
                Name.Literal("a.o"),
            ),
        )

    assertEquals(
        expected =
            RelativeUnixPath.of(
                Name.Literal("build"),
                Name.Literal("artifacts"),
                Name.Literal("intermediate"),
                Name.Literal("a.o"),
            ),
        actual = concatenatedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_construct_emptyInner() {
    assertEquals(
        expected =
            AbsoluteUnixPath(
                RelativeUnixPath.Empty,
            ),
        actual = AbsoluteUnixPath.Root,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_empty() {
    assertIs<IllegalArgumentException>(
        assertFails { AbsoluteUnixPath.parse("") },
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_root() {
    val parsedPath: AbsoluteUnixPath<Name> = AbsoluteUnixPath.parse("/")

    assertEquals(
        expected = AbsoluteUnixPath.Root,
        actual = parsedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_toUnixAbsolutePathString_root() {
    assertEquals(
        expected = "/",
        actual = AbsoluteUnixPath.Root.toUnixAbsolutePathString(),
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_root_double() {
    assertIs<IllegalArgumentException>(
        assertFails { AbsoluteUnixPath.parse("//") },
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_posix_special() {
    assertIs<IllegalArgumentException>(
        assertFails { AbsoluteUnixPath.parse("//posix/special/path") },
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_topLevel() {
    val parsedPath: AbsoluteUnixPath<Name> = AbsoluteUnixPath.parse("/tmp")

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("tmp"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_trailingSeparator() {
    assertIs<IllegalArgumentException>(
        assertFails { AbsoluteUnixPath.parse("/tmp/") },
    )
  }

  @Test
  fun test_AbsoluteUnixPath_toUnixAbsolutePathString_topLevel() {
    val unixPath =
        AbsoluteUnixPath.of(
            Name.Literal("tmp"),
        )

    assertEquals(
        expected = "/tmp",
        actual = unixPath.toUnixAbsolutePathString(),
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_nested() {
    val parsedPath: AbsoluteUnixPath<Name> = AbsoluteUnixPath.parse("/usr/lib")

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("usr"),
                Name.Literal("lib"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_toUnixAbsolutePathString_nested() {
    val unixPath =
        AbsoluteUnixPath.of(
            Name.Literal("usr"),
            Name.Literal("lib"),
        )

    assertEquals(
        expected = "/usr/lib",
        actual = unixPath.toUnixAbsolutePathString(),
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_symbolicNames() {
    val parsedPath: AbsoluteUnixPath<Name> = AbsoluteUnixPath.parse("/usr/./lib/..")

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("usr"),
                Name.Symbolic.ThisDirectory,
                Name.Literal("lib"),
                Name.Symbolic.ParentDirectory,
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_toUnixAbsolutePathString_symbolicNames() {
    val unixPath =
        AbsoluteUnixPath.of(
            Name.Literal("usr"),
            Name.Symbolic.ThisDirectory,
            Name.Literal("lib"),
            Name.Symbolic.ParentDirectory,
        )

    assertEquals(
        expected = "/usr/./lib/..",
        actual = unixPath.toUnixAbsolutePathString(),
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_consecutive_separator() {
    assertIs<IllegalArgumentException>(
        assertFails { AbsoluteUnixPath.parse("/usr/bin//vim") },
    )
  }

  @Test
  fun test_AbsoluteUnixPath_parse_deeplyNested() {
    val parsedPath: AbsoluteUnixPath<Name> = AbsoluteUnixPath.parse("/usr/local/bin/vim")

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("usr"),
                Name.Literal("local"),
                Name.Literal("bin"),
                Name.Literal("vim"),
            ),
        actual = parsedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_toUnixAbsolutePathString_deeplyNested() {
    val unixPath =
        AbsoluteUnixPath.of(
            Name.Literal("usr"),
            Name.Literal("local"),
            Name.Literal("bin"),
            Name.Literal("vim"),
        )

    assertEquals(
        expected = "/usr/local/bin/vim",
        actual = unixPath.toUnixAbsolutePathString(),
    )
  }

  @Test
  fun test_AbsoluteUnixPath_resolve_root_empty() {
    val resolvedPath = AbsoluteUnixPath.Root.resolve(RelativeUnixPath.Empty)

    assertEquals(
        expected = AbsoluteUnixPath.Root,
        actual = resolvedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_resolve_root_proper() {
    val resolvedPath =
        AbsoluteUnixPath.Root.resolve(
            RelativeUnixPath.of(
                Name.Literal("tmp"),
            ),
        )

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("tmp"),
            ),
        actual = resolvedPath,
    )
  }

  @Test
  fun test_AbsoluteUnixPath_resolve_nested() {
    val resolvedPath =
        AbsoluteUnixPath.of(
                Name.Literal("tmp"),
            )
            .resolve(
                RelativeUnixPath.of(
                    Name.Literal("tmpdir-123"),
                    Name.Literal("file.txt"),
                ),
            )

    assertEquals(
        expected =
            AbsoluteUnixPath.of(
                Name.Literal("tmp"),
                Name.Literal("tmpdir-123"),
                Name.Literal("file.txt"),
            ),
        actual = resolvedPath,
    )
  }
}

package software.medusa.flow.githubstub

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A local bare git repo standing in for a GitHub remote — what the worker clones from and pushes
 * to. Promotes the M1 publisher-test trick to a shared fixture.
 *
 * The default branch is pinned explicitly (`git init --bare -b main`) rather than relying on the
 * machine's `init.defaultBranch`: a plain push never moves a bare repo's `HEAD` to follow a newly
 * pushed branch, so a clone would otherwise land on an unborn `HEAD` (the CI lesson from
 * CLAUDE.md).
 */
class BareRepoFixture
private constructor(
    /** Absolute path to the bare repo; usable directly as a `git clone`/`push` remote. */
    val remotePath: Path,
) {
  /** A `file://` URL for the remote, for callers that want a URL rather than a path. */
  val remoteUrl: String
    get() = remotePath.toUri().toString()

  /** The tip commit SHA of [branch] on the remote (e.g. to seed a merge-commit SHA). */
  fun tipSha(
      branch: String = defaultBranch,
  ): String = git(remotePath, "rev-parse", branch).trim()

  /** Whether [branch] exists on the remote (e.g. to assert a push landed). */
  fun hasBranch(
      branch: String,
  ): Boolean = runCatching { tipSha(branch) }.isSuccess

  /** Reads a file's content at [branch]'s tip (e.g. to assert what the worker pushed). */
  fun showFile(
      path: String,
      branch: String = defaultBranch,
  ): String = git(remotePath, "show", "$branch:$path")

  companion object {
    const val defaultBranch = "main"

    /**
     * Creates a bare repo under [parentDirectory], seeded with an initial commit of everything
     * under [seedDirectory] on branch [defaultBranch]. [seedDirectory] is a plain project directory
     * (a fixture); its `.git`, if any, is ignored.
     */
    fun create(
        parentDirectory: Path,
        seedDirectory: Path,
    ): BareRepoFixture {
      require(seedDirectory.isDirectory()) { "seed directory does not exist: $seedDirectory" }

      val bareDir = Files.createTempDirectory(parentDirectory, "gh-stub-bare-")
      git(bareDir, "init", "--bare", "-q", "-b", defaultBranch)

      // Seed via a throwaway working clone: copy the fixture tree in, commit, push.
      val seedWork = Files.createTempDirectory(parentDirectory, "gh-stub-seed-")
      git(seedWork, "init", "-q", "-b", defaultBranch)
      copyTree(from = seedDirectory, into = seedWork)
      git(seedWork, "add", "-A")
      commit(seedWork, "seed")
      git(seedWork, "push", "-q", bareDir.toString(), defaultBranch)

      return BareRepoFixture(remotePath = bareDir)
    }

    private fun copyTree(
        from: Path,
        into: Path,
    ) {
      Files.walk(from).use { stream ->
        stream.forEach { source ->
          if (source == from || source.any { it.toString() == ".git" }) return@forEach
          val target = into.resolve(from.relativize(source).toString())
          if (source.isDirectory()) {
            if (!target.exists()) Files.createDirectories(target)
          } else {
            Files.createDirectories(target.parent)
            Files.copy(source, target)
            // Preserve the executable bit (gradlew et al.) so the seeded baseline builds.
            if (Files.isExecutable(source)) target.toFile().setExecutable(true, false)
          }
        }
      }
    }

    private fun commit(
        workingDir: Path,
        message: String,
    ) {
      git(
          workingDir,
          "-c",
          "user.name=gh-stub",
          "-c",
          "user.email=gh-stub@example.com",
          "-c",
          "commit.gpgsign=false",
          "commit",
          "-q",
          "-m",
          message,
      )
    }

    internal fun git(
        directory: Path,
        vararg args: String,
    ): String {
      val process =
          ProcessBuilder(listOf("git", *args))
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .start()
      val output = process.inputStream.bufferedReader().readText()
      val exit = process.waitFor()
      check(exit == 0) {
        "git ${args.joinToString(" ")} failed in $directory (exit $exit):\n$output"
      }
      return output
    }
  }
}

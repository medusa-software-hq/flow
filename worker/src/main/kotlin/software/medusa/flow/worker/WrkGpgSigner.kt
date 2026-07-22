package software.medusa.flow.worker

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-signs a commit with a GPG key via the `git` CLI. This is a deliberate work-around: the
 * in-house git library that creates the commit can't sign yet, and the branch ruleset requires
 * signed commits, so a worker whose signing is enabled amends its freshly-made HEAD with a
 * signature here.
 *
 * The key ([WrkConfig.gpgPrivateKey], an ASCII-armored, **passphrase-less** private key) is
 * imported into a throwaway `GNUPGHOME` that is wiped afterwards — nothing lands in the worker's
 * real keyring. Requires `gpg` on `PATH` (the worker image ships it).
 */
internal object WrkGpgSigner {
  /**
   * Amends [cloneDirectory]'s HEAD so it carries a GPG signature from [privateKey]. Author,
   * message, and tree are preserved; only the signature (and committer) are added. Throws on any
   * gpg/git failure — a signing worker must fail loudly rather than push an unsigned commit that
   * the ruleset will reject anyway.
   */
  suspend fun reSignHead(
      cloneDirectory: File,
      privateKey: String,
      authorName: String,
      authorEmail: String,
      gitHubToken: suspend () -> String,
  ) {
    withContext(Dispatchers.IO) {
      // gpg-agent's socket lives inside GNUPGHOME, and a Unix socket path is capped near 104 chars,
      // so keep the home under a short base (`/tmp`) rather than the JVM default temp dir, which is
      // long on some platforms and makes the agent fail to start.
      val gnupgHome = Files.createTempDirectory(java.nio.file.Path.of("/tmp"), "wrk-gnupg-")
      Files.setPosixFilePermissions(
          gnupgHome,
          setOf(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
          ),
      )
      try {
        importKey(gnupgHome = gnupgHome.toString(), privateKey = privateKey)
        val fingerprint = secretKeyFingerprint(gnupgHome = gnupgHome.toString())

        WrkGitProcess.run(
            cloneDirectory,
            gitHubToken,
            // The amend resets the committer, so the identity must be supplied here too (the clone
            // has none configured); --no-edit keeps the original author, message, and tree.
            "-c",
            "user.name=$authorName",
            "-c",
            "user.email=$authorEmail",
            "-c",
            "gpg.program=gpg",
            "-c",
            "user.signingkey=$fingerprint",
            "-c",
            "commit.gpgsign=true",
            "commit",
            "--amend",
            "--no-edit",
            env = mapOf("GNUPGHOME" to gnupgHome.toString()),
        )
      } finally {
        gnupgHome.toFile().deleteRecursively()
      }
    }
  }

  private fun importKey(gnupgHome: String, privateKey: String) {
    val process =
        ProcessBuilder("gpg", "--homedir", gnupgHome, "--batch", "--import")
            .redirectErrorStream(true)
            .start()
    process.outputStream.use { it.write(privateKey.toByteArray()) }
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "gpg key import failed:\n$output" }
  }

  /** The fingerprint of the (single) imported secret key — what `user.signingkey` needs. */
  private fun secretKeyFingerprint(gnupgHome: String): String {
    val process =
        ProcessBuilder(
                "gpg",
                "--homedir",
                gnupgHome,
                "--batch",
                "--with-colons",
                "--list-secret-keys",
            )
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "gpg secret-key listing failed:\n$output" }

    // `fpr` records are `fpr:::::::::<FINGERPRINT>:` — field 10 (0-indexed 9).
    return output
        .lineSequence()
        .filter { it.startsWith("fpr:") }
        .mapNotNull { it.split(":").getOrNull(9)?.takeIf(String::isNotBlank) }
        .firstOrNull() ?: error("no secret-key fingerprint found after import:\n$output")
  }
}

package software.medusa.flow.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val configDirName = "flow"
private const val credentialsFileName = "credentials.json"

private val credentialsJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
}

/**
 * Cached Flow sign-in: the long-lived refresh token plus the most recent ID token and its expiry.
 * The refresh token is the sensitive bit — it stands in for the human until revoked — so the file
 * is written 0600 (and its directory 0700).
 */
@Serializable
data class FlowCredentials(
    val refreshToken: String,
    val idToken: String,
    val idTokenExpiresAtEpochSec: Long,
    val email: String,
)

/** `$XDG_CONFIG_HOME/flow/`, falling back to `~/.config/flow/` — also on macOS. */
fun flowConfigDir(
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
    userHome: String = System.getProperty("user.home"),
): Path {
  val base =
      if (!xdgConfigHome.isNullOrBlank()) {
        Path.of(xdgConfigHome)
      } else {
        Path.of(userHome, ".config")
      }
  return base.resolve(configDirName)
}

fun flowCredentialsFile(dir: Path = flowConfigDir()): Path = dir.resolve(credentialsFileName)

fun loadFlowCredentials(dir: Path = flowConfigDir()): FlowCredentials? {
  val file = flowCredentialsFile(dir)
  if (!Files.exists(file)) return null
  return credentialsJson.decodeFromString(Files.readString(file))
}

/** Writes [credentials] with dir 0700 / file 0600, set atomically at creation where supported. */
fun saveFlowCredentials(credentials: FlowCredentials, dir: Path = flowConfigDir()) {
  if (!Files.exists(dir)) {
    runCatching {
          Files.createDirectory(
              dir,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
          )
        }
        .getOrElse { Files.createDirectories(dir) }
  }

  val file = flowCredentialsFile(dir)
  Files.deleteIfExists(file)
  runCatching {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
      }
      .getOrElse { Files.createFile(file) }
  Files.writeString(file, credentialsJson.encodeToString(credentials))
}

fun deleteFlowCredentials(dir: Path = flowConfigDir()) {
  Files.deleteIfExists(flowCredentialsFile(dir))
}

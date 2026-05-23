package software.medusa.commons.filesystem.tech

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.asReadOnlyByteBuffer
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile

/** Content classification for technical files stored in the compat filesystem. */
sealed interface TechFileContent {
  data class Utf8Text(
      val text: String,
  ) : TechFileContent

  data object Binary : TechFileContent
}

/**
 * Reads file contents and classifies them naively as either valid UTF-8 text or binary data.
 */
suspend fun ReadonlyCompatFsFile.readTechContent(): TechFileContent {
  val byteContent = read()

  return try {
    val text =
        withContext(Dispatchers.IO) {
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(byteContent.asReadOnlyByteBuffer())
              .toString()
        }

    TechFileContent.Utf8Text(text = text)
  } catch (_: CharacterCodingException) {
    TechFileContent.Binary
  }
}

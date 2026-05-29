package software.medusa.commons.filesystem.tech

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.asReadOnlyByteBuffer
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile

/** Content classification for technical files stored in the compat filesystem. */
sealed interface TechFileContent {
  /**
   * Content of a code file.
   *
   * For simplicity, every UTF-8 file is treated as code and represented as a line-based block.
   */
  @JvmInline
  value class Code(
      /** A single block containing the whole file content. */
      val code: CodeBlock,
  ) : TechFileContent {
    companion object {
      /** A code file with no content (i.e. an empty file). */
      val Empty = Code(code = CodeBlock.Empty)

      fun of(
          vararg lines: String,
      ): Code = of(lines = lines.toList())

      fun of(
          lines: List<String>,
      ): Code =
          Code(
              code = CodeBlock.of(lines = lines),
          )

      fun parse(
          rawContent: String,
      ): Code =
          Code(
              code = CodeBlock.parse(rawContent = rawContent),
          )
    }

    val indexedLines: Sequence<CodeBlock.IndexedLine>
      get() = code.buildIndexedLines(baseIndex = CodeBlock.LineIndex.First)

    /** Dumps the content of the code file as a string with LF-terminated lines. */
    fun dump(): String = code.dump()
  }

  data object Binary : TechFileContent
}

/** Reads file contents and classifies them naively as either valid UTF-8 code or binary data. */
suspend fun ReadonlyCompatFsFile.readTechContent(): TechFileContent {
  val byteContent = read()

  return try {
    val text =
        withContext(Dispatchers.IO) {
          StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(byteContent.asReadOnlyByteBuffer())
              .toString()
        }

    TechFileContent.Code.parse(rawContent = text)
  } catch (_: CharacterCodingException) {
    TechFileContent.Binary
  } catch (_: CodeBlock.IllegalCodeContentException) {
    TechFileContent.Binary
  }
}

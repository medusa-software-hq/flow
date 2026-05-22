package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream
import software.medusa.commons.serialization.ccon.parsing.consume
import software.medusa.commons.unicode.ControlChar

data class CconString(
    val content: String,
) : CconElement() {
  companion object {
    const val startMarkerChar = ControlChar.SO
    const val endMarkerChar = ControlChar.SI

    fun isSafe(char: Char): Boolean = char == '\t' || !char.isISOControl()
  }

  internal object Parser : CconSpecificElementParser<CconString>() {
    override val startMarkerChar = CconString.startMarkerChar

    context(charStream: CharStream)
    override fun parse(): CconString {
      charStream.consume(startMarkerChar) {
        throw CconParseException.build("String", "Expected start marker '$startMarkerChar'")
      }

      val content = charStream.extract(::isSafe)

      charStream.consume(endMarkerChar) {
        throw CconParseException.build("String", "Expected end marker '$endMarkerChar'")
      }

      return CconString(
          content = content,
      )
    }
  }

  init {
    require(content.all(::isSafe)) {
      "Content of a CconString can only contain non-control characters or tabs, but found '${content.firstOrNull(::isSafe)}'"
    }
  }

  override fun encodeToString(): String = buildString {
    append(startMarkerChar)
    append(content)
    append(endMarkerChar)
  }
}

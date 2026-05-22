package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream
import software.medusa.commons.serialization.ccon.parsing.consume
import software.medusa.commons.unicode.ControlChar

data class CconRecord(
    val tagName: String,
    val headerValues: List<String>,
    val childElements: List<CconElement>,
) : CconElement() {
  companion object {
    const val startMarkerChar = ControlChar.SOH
    const val headerStartMarkerChar = ControlChar.RS
    const val headerValueSeparatorMarkerChar = ControlChar.US
    const val contentStartMarkerChar = ControlChar.STX
    const val childElementSeparatorMarkerChar = ControlChar.GS
    const val contentEndMarkerChar = ControlChar.ETX
    const val endMarkerChar = ControlChar.EOT

    fun isSafeRawChar(char: Char): Boolean = CconString.isSafe(char)
  }

  init {
    require(tagName.isNotEmpty()) { "Tag name must not be empty" }
    require(tagName.all(::isSafeRawChar)) { "Tag name cannot contain control characters" }
    require(headerValues.all { headerValue -> headerValue.all(::isSafeRawChar) }) {
      "Header values cannot contain control characters"
    }
  }

  internal object Parser : CconSpecificElementParser<CconRecord>() {
    override val startMarkerChar = CconRecord.startMarkerChar

    context(charStream: CharStream)
    override fun parse(): CconRecord {
      charStream.consume(startMarkerChar) {
        throw CconParseException.build("Record", "Expected start marker '$startMarkerChar'")
      }

      val tagName = charStream.extract(::isSafeRawChar)

      if (tagName.isEmpty()) {
        throw CconParseException.build("Record", "Expected non-empty tag name")
      }

      charStream.consume(headerStartMarkerChar) {
        throw CconParseException.build(
            "Record",
            "Expected header start marker '$headerStartMarkerChar'",
        )
      }

      val headerValues = parseHeaderValues()

      charStream.consume(contentStartMarkerChar) {
        throw CconParseException.build(
            "Record",
            "Expected content start marker '$contentStartMarkerChar'",
        )
      }

      val childElements = parseChildElements()

      charStream.consume(contentEndMarkerChar) {
        throw CconParseException.build(
            "Record",
            "Expected content end marker '$contentEndMarkerChar'",
        )
      }

      val closingTagName = charStream.extract(::isSafeRawChar)

      if (closingTagName != tagName) {
        throw CconParseException.build(
            "Record",
            "Expected closing tag '$tagName', but found '$closingTagName'",
        )
      }

      charStream.consume(endMarkerChar) {
        throw CconParseException.build("Record", "Expected end marker '$endMarkerChar'")
      }

      return CconRecord(
          tagName = tagName,
          headerValues = headerValues,
          childElements = childElements,
      )
    }

    context(charStream: CharStream)
    private fun parseHeaderValues(): List<String> {
      if (charStream.peek() == contentStartMarkerChar) {
        return emptyList()
      }

      return buildList {
        while (true) {
          add(charStream.extract(::isSafeRawChar))

          when (val nextChar = charStream.peek()) {
            headerValueSeparatorMarkerChar -> charStream.discard()
            contentStartMarkerChar -> break
            null ->
                throw CconParseException.build(
                    "Record",
                    "Unexpected end of input while parsing header values",
                )
            else ->
                throw CconParseException.build(
                    "Record",
                    "Expected header value separator '$headerValueSeparatorMarkerChar' or content start marker '$contentStartMarkerChar', but found '$nextChar'",
                )
          }
        }
      }
    }

    context(charStream: CharStream)
    private fun parseChildElements(): List<CconElement> {
      if (charStream.peek() == contentEndMarkerChar) {
        return emptyList()
      }

      return buildList {
        add(CconElement.Parser.parse())

        while (true) {
          when (val nextChar = charStream.peek()) {
            childElementSeparatorMarkerChar -> {
              charStream.discard()
              add(CconElement.Parser.parse())
            }

            contentEndMarkerChar -> break

            null ->
                throw CconParseException.build(
                    "Record",
                    "Unexpected end of input while parsing child elements",
                )

            else ->
                throw CconParseException.build(
                    "Record",
                    "Expected child element separator '$childElementSeparatorMarkerChar' or content end marker '$contentEndMarkerChar', but found '$nextChar'",
                )
          }
        }
      }
    }
  }

  override fun encodeToString(): String = buildString {
    append(startMarkerChar)
    append(tagName)
    append(headerStartMarkerChar)
    append(headerValues.joinToString(separator = headerValueSeparatorMarkerChar.toString()))
    append(contentStartMarkerChar)
    append(
        childElements.joinToString(separator = childElementSeparatorMarkerChar.toString()) {
          it.encodeToString()
        }
    )
    append(contentEndMarkerChar)
    append(tagName)
    append(endMarkerChar)
  }
}

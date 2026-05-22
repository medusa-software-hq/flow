package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream

internal interface CconElementParser<ElementT : CconElement> {
  context(charStream: CharStream)
  fun parse(): ElementT
}

internal fun <ElementT : CconElement> CconElementParser<ElementT>.parse(input: String): ElementT {
  val charStream = CharStream(input = input)

  return with(charStream) {
    val element = parse()

    if (peek() != null) {
      throw CconParseException.build("Root", "Unexpected trailing character '${peek()}'")
    }

    element
  }
}

internal fun <ElementT : CconElement> CharStream.consume(
    parser: CconElementParser<ElementT>,
): ElementT = parser.parse()

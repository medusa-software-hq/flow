package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream

internal sealed class CconSpecificElementParser<ElementT : CconElement> :
    CconElementParser<ElementT> {
  context(charStream: CharStream)
  fun isRelevant(): Boolean = charStream.peek() == startMarkerChar

  abstract val startMarkerChar: Char
}

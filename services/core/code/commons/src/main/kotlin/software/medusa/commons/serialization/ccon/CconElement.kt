package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream

sealed class CconElement {
  companion object {
    val ebnfGrammarDescription =
        """
      element = record | string ;
      
      record = "${CconRecord.startMarkerChar}" , tag_name , "${CconRecord.headerStartMarkerChar}" , [ header_value { "${CconRecord.headerValueSeparatorMarkerChar}" , header_value } ] , "${CconRecord.contentStartMarkerChar}" , [ element { "${CconRecord.childElementSeparatorMarkerChar}" , element } ] , "${CconRecord.contentEndMarkerChar}" , tag_name , "${CconRecord.endMarkerChar}" ;
      tag_name = raw_text ;
      header_value = raw_text ;
      
      string = "${CconString.startMarkerChar}" , raw_text , "${CconString.endMarkerChar}" ;
      
      raw_text = { non_control_char | tab } ;
    """
            .trimIndent()

    fun decodeFromString(
        cconString: String,
    ): CconElement = Parser.parse(input = cconString)
  }

  internal object Parser : CconElementParser<CconElement> {
    context(charStream: CharStream)
    override fun parse(): CconElement {
      val relevantParser =
          when {
            CconRecord.Parser.isRelevant() -> CconRecord.Parser
            CconString.Parser.isRelevant() -> CconString.Parser
            else ->
                throw CconParseException.build(
                    "Element",
                    "Unexpected character '${charStream.peek()}'",
                )
          }

      return relevantParser.parse()
    }
  }

  abstract fun encodeToString(): String
}

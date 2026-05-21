package software.medusa.commons.serialization.ccon

import software.medusa.commons.serialization.ccon.parsing.CharStream

class CconParseException
private constructor(
    val context: String,
    val text: String,
    val offset: Int,
) : Exception("$text [context = ${context}, offset = $offset]") {
  internal companion object {
    context(charStream: CharStream)
    fun build(
        context: String,
        text: String,
    ): CconParseException =
        CconParseException(
            context = context,
            text = text,
            offset = charStream.currentOffset,
        )
  }
}

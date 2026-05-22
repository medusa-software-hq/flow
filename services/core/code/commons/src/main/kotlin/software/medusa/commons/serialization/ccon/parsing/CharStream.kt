package software.medusa.commons.serialization.ccon.parsing

internal class CharStream(
    private val input: String,
) {
  private var offset: Int = 0

  val currentOffset: Int
    get() = offset

  fun peek(): Char? = input.getOrNull(offset)

  fun extract(
      predicate: (Char) -> Boolean,
  ): String {
    val startOffset = offset

    while (true) {
      val nextChar = input.getOrNull(offset) ?: break

      if (!predicate(nextChar)) {
        break
      }

      offset += 1
    }

    return input.substring(startOffset, offset)
  }

  fun discard() {
    offset += 1
  }
}

internal fun CharStream.consume(
    markerChar: Char,
    orElse: () -> Nothing,
) {
  when {
    peek() == markerChar -> {
      discard()
    }

    else -> orElse()
  }
}

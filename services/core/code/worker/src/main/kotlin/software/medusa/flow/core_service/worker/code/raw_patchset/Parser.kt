package software.medusa.flow.core_service.worker.code.raw_patchset

interface Parser<T : Any> {
  context(charStream: CharStream)
  fun parse(): T
}

fun <T : Any> Parser<T>.parse(input: String): T {
  val charStream = CharStream(input = input)

  return with(charStream) { parse() }
}

/** Parse a non-empty sequence of `T` separated by `delimiter. */
context(charStream: CharStream)
fun <T : Any> Parser<T>.parseSequence(delimiter: Char): Sequence<T> = sequence {
  while (true) {
    yield(parse())

    if (charStream.peek() == delimiter) {
      charStream.discard(1)

      continue
    } else {
      return@sequence
    }
  }
}

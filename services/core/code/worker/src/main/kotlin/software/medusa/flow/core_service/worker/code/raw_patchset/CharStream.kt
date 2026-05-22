package software.medusa.flow.core_service.worker.code.raw_patchset

class CharStream(
    private val input: String,
) {
  interface CharClass {
    data object Control : CharClass {
      override operator fun contains(char: Char): Boolean = char.isISOControl()
    }

    data object Digit : CharClass {
      override operator fun contains(char: Char): Boolean = char.isDigit()
    }

    companion object {
      val NonControl = Control.closure

      val CharClass.closure: CharClass
        get() =
            object : CharClass {
              override operator fun contains(char: Char): Boolean = !this@closure.contains(char)
            }
    }

    fun contains(char: Char): Boolean
  }

  private var offset: Int = 0

  val currentOffset: Int
    get() = offset

  fun startsWith(prefix: String): Boolean = input.startsWith(prefix, offset)

  fun peek(): Char? = input.getOrNull(offset)

  fun extract(
      charClass: CharClass,
  ): String {
    val startOffset = offset

    while (true) {
      val nextChar = input.getOrNull(offset) ?: break

      if (!charClass.contains(nextChar)) {
        break
      }

      offset += 1
    }

    return input.substring(startOffset, offset)
  }

  fun discard(count: Int) {
    offset += count
  }
}

fun CharStream.consume(
    prefix: String,
    orElse: () -> Nothing,
) {
  when {
    startsWith(prefix) -> {
      discard(prefix.length)
    }

    else -> orElse()
  }
}

fun CharStream.consume(
    prefix: Char,
    orElse: () -> Nothing,
) {
  when {
    peek() == prefix -> {
      discard(1)
    }

    else -> orElse()
  }
}

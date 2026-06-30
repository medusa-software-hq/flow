package software.medusa.flow.virtual_editor

@JvmInline
value class VedTimestamp(
    val t: Int,
) {
  companion object {
    val zero = VedTimestamp(t = 0)
  }

  init {
    require(t >= 0) { "Timestamp must be non-negative" }
  }

  val next: VedTimestamp
    get() = VedTimestamp(t + 1)
}

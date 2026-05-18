package software.medusa.flow.core_service.worker.code.tc

@JvmInline
value class TcString(
    val content: String,
) {
  init {
    require(content.none { ControlChar.isControl(it) }) {
      "TcString content cannot contain control characters, but found: ${content.filter { ControlChar.isControl(it) }}"
    }
  }
}

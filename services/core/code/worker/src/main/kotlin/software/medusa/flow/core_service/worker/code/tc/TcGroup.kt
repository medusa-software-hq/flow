package software.medusa.flow.core_service.worker.code.tc

import software.medusa.flow.core_service.worker.code.CodeBlock

data class TcGroup(
    val records: List<TcRecord>
) {
  companion object {
    fun of(
        block: CodeBlock,
    ): TcGroup = TcGroup(
        records = listOf(
            TcRecord.of(block = block),
        ),
    )
  }

  fun encode(): String =
      records.joinToString(separator = ControlChar.RS.toString()) { record ->
        record.encode()
      }
}

package software.medusa.flow.core_service.worker.code.tc

import software.medusa.flow.core_service.worker.code.CodeBlock

data class TcRecord(
    val units: List<TcUnit>
) {
  companion object {
    fun of(
        block: CodeBlock,
    ): TcRecord = TcRecord(
        units = listOf(
            TcUnit.of(block = block),
        ),
    )
  }

  fun encode(): String =
      units.joinToString(separator = ControlChar.US.toString()) { unit ->
        unit.block.dump()
      }
}

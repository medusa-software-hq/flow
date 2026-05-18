package software.medusa.flow.core_service.worker.code.tc

import software.medusa.flow.core_service.worker.code.CodeBlock

data class TcUnit(
    val block: CodeBlock,
) {
  companion object {
    fun of(
        block: CodeBlock,
    ): TcUnit =
        TcUnit(
            block = block,
        )
  }
}

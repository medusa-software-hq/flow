package software.medusa.flow.core_service.worker.code.tc

data class TcUnit(
    val value: TcString,
) {
  companion object {
    fun of(
        value: TcString,
    ): TcUnit =
        TcUnit(
            value = value,
        )
  }
}

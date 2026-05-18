package software.medusa.flow.core_service.worker.code.tc

data class TcRecord(val units: List<TcUnit>) {
  companion object {
    fun of(
        value: TcString,
    ): TcRecord =
        TcRecord(
            units =
                listOf(
                    TcUnit.of(value = value),
                ),
        )
  }

  fun encodeToString(): String =
      units.joinToString(separator = ControlChar.US.toString()) { unit -> unit.value.content }
}

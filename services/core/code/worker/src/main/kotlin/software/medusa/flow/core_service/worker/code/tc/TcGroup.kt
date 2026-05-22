package software.medusa.flow.core_service.worker.code.tc

data class TcGroup(val records: List<TcRecord>) {
  companion object {
    fun of(
        value: TcString,
    ): TcGroup =
        TcGroup(
            records =
                listOf(
                    TcRecord.of(value = value),
                ),
        )
  }

  fun encodeToString(): String =
      records.joinToString(separator = ControlChar.RS.toString()) { record ->
        record.encodeToString()
      }
}

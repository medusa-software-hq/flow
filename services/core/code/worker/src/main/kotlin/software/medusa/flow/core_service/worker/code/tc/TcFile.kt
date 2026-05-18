package software.medusa.flow.core_service.worker.code.tc

data class TcFile(val groups: List<TcGroup>) {
  fun encodeToString(): String =
      groups.joinToString(separator = ControlChar.GS.toString()) { group -> group.encodeToString() }
}

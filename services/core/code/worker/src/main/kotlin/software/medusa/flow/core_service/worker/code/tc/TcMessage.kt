package software.medusa.flow.core_service.worker.code.tc

data class TcMessage(val header: TcString, val files: List<TcFile>) {
  fun encodeToString(): String =
      ControlChar.SOH +
          header.content +
          ControlChar.STX +
          files.joinToString(separator = ControlChar.FS.toString()) { file ->
            file.encodeToString()
          } +
          ControlChar.ETX
}

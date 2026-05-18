package software.medusa.flow.core_service.worker.code.tc

import software.medusa.flow.core_service.worker.code.CodeBlock

data class TcMessage(val header: CodeBlock, val files: List<TcFile>) {
  fun encodeToString(): String =
      ControlChar.SOH +
          header.dump() +
          ControlChar.STX +
          files.joinToString(separator = ControlChar.FS.toString()) { file ->
            file.encodeToString()
          } +
          ControlChar.ETX
}

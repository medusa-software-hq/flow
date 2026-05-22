package software.medusa.flow.core_service.worker.code.tc

object ControlChar {
  const val NUL: Char = '\u0000'
  const val SOH: Char = '\u0001'
  const val STX: Char = '\u0002'
  const val ETX: Char = '\u0003'
  const val EOT: Char = '\u0004'
  const val ENQ: Char = '\u0005'
  const val ACK: Char = '\u0006'
  const val BEL: Char = '\u0007'
  const val BS: Char = '\u0008'
  const val HT: Char = '\u0009'
  const val LF: Char = '\u000A'
  const val VT: Char = '\u000B'
  const val FF: Char = '\u000C'
  const val CR: Char = '\u000D'
  const val SO: Char = '\u000E'
  const val SI: Char = '\u000F'
  const val DLE: Char = '\u0010'
  const val DC1: Char = '\u0011'
  const val DC2: Char = '\u0012'
  const val DC3: Char = '\u0013'
  const val DC4: Char = '\u0014'
  const val NAK: Char = '\u0015'
  const val SYN: Char = '\u0016'
  const val ETB: Char = '\u0017'
  const val CAN: Char = '\u0018'
  const val EM: Char = '\u0019'
  const val SUB: Char = '\u001A'
  const val ESC: Char = '\u001B'
  const val FS: Char = '\u001C'
  const val GS: Char = '\u001D'
  const val RS: Char = '\u001E'
  const val US: Char = '\u001F'

  const val DEL: Char = '\u007F'

  const val ST: Char = '\u009C'

  val All: List<Char> =
      listOf(
          NUL,
          SOH,
          STX,
          ETX,
          EOT,
          ENQ,
          ACK,
          BEL,
          BS,
          HT,
          LF,
          VT,
          FF,
          CR,
          SO,
          SI,
          DLE,
          DC1,
          DC2,
          DC3,
          DC4,
          NAK,
          SYN,
          ETB,
          CAN,
          EM,
          SUB,
          ESC,
          FS,
          GS,
          RS,
          US,
      )

  fun isControlC0(char: Char): Boolean = char.code in 0x00..0x1F

  fun isControl(char: Char): Boolean = char.code in 0x00..0x1F || char == DEL
}

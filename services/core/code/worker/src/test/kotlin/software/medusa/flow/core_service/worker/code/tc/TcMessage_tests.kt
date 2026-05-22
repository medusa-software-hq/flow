package software.medusa.flow.core_service.worker.code.tc

import kotlin.test.Test
import kotlin.test.assertEquals

class TcMessage_tests {
  @Test
  fun test_encodeToString_singleFileSingleValue() {
    val message =
        TcMessage(
            header = TcString("header"),
            files =
                listOf(
                    TcFile(
                        groups =
                            listOf(
                                TcGroup.of(
                                    value = TcString("file.txt"),
                                ),
                            ),
                    ),
                ),
        )

    assertEquals(
        expected = "${ControlChar.SOH}header${ControlChar.STX}file.txt${ControlChar.ETX}",
        actual = message.encodeToString(),
    )
  }

  @Test
  fun test_encodeToString_uses_all_tc_separators() {
    val message =
        TcMessage(
            header = TcString("hdr"),
            files =
                listOf(
                    TcFile(
                        groups =
                            listOf(
                                TcGroup(
                                    records =
                                        listOf(
                                            TcRecord(
                                                units =
                                                    listOf(
                                                        TcUnit.of(TcString("a1")),
                                                        TcUnit.of(TcString("a2")),
                                                    ),
                                            ),
                                            TcRecord(
                                                units =
                                                    listOf(
                                                        TcUnit.of(TcString("b1")),
                                                    ),
                                            ),
                                        ),
                                ),
                                TcGroup.of(TcString("g2")),
                            ),
                    ),
                    TcFile(
                        groups =
                            listOf(
                                TcGroup.of(TcString("file2")),
                            ),
                    ),
                ),
        )

    assertEquals(
        expected =
            buildString {
              append(ControlChar.SOH)
              append("hdr")
              append(ControlChar.STX)
              append("a1")
              append(ControlChar.US)
              append("a2")
              append(ControlChar.RS)
              append("b1")
              append(ControlChar.GS)
              append("g2")
              append(ControlChar.FS)
              append("file2")
              append(ControlChar.ETX)
            },
        actual = message.encodeToString(),
    )
  }
}

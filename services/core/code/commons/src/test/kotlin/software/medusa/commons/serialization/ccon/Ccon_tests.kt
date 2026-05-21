package software.medusa.commons.serialization.ccon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import software.medusa.commons.unicode.ControlChar

class Ccon_tests {
  @Test
  fun test_CconString_construct_plainText() {
    val string = CconString(content = "hello, world")

    assertEquals(expected = "hello, world", actual = string.content)
  }

  @Test
  fun test_CconString_construct_tabIsAllowed() {
    val string = CconString(content = "left\tright")

    assertEquals(expected = "left\tright", actual = string.content)
  }

  @Test
  fun test_CconString_construct_controlCharIsRejected() {
    val exception =
        assertThrows<IllegalArgumentException> {
          CconString(content = "bad${ControlChar.LF}content")
        }

    assertTrue(
        exception.message!!.contains("CconString can only contain non-control characters or tabs")
    )
  }

  @Test
  fun test_CconString_isSafe() {
    assertTrue(CconString.isSafe('a'))
    assertTrue(CconString.isSafe('\t'))
    assertFalse(CconString.isSafe(ControlChar.NUL))
    assertFalse(CconString.isSafe(ControlChar.LF))
  }

  @Test
  fun test_CconString_encodeToString_wrapsContentInMarkers() {
    val encoded = CconString(content = "hello\tworld").encodeToString()

    assertEquals(
        expected = "${ControlChar.SO}hello\tworld${ControlChar.SI}",
        actual = encoded,
    )
  }

  @Test
  fun test_CconRecord_encodeToString_wrapsTagHeadersChildrenAndClosingTag() {
    val encoded =
        CconRecord(
                tagName = "entry",
                headerValues = listOf("alpha", "beta"),
                childElements = listOf(CconString(content = "payload")),
            )
            .encodeToString()

    assertEquals(
        expected =
            "${ControlChar.SOH}entry${ControlChar.RS}alpha${ControlChar.US}beta${ControlChar.STX}${ControlChar.SO}payload${ControlChar.SI}${ControlChar.ETX}entry${ControlChar.EOT}",
        actual = encoded,
    )
  }

  @Test
  fun test_CconElement_decodeFromString_parseStringRoot() {
    val parsed = CconElement.decodeFromString("${ControlChar.SO}hello${ControlChar.SI}")

    assertEquals(CconString(content = "hello"), parsed)
  }

  @Test
  fun test_CconElement_decodeFromString_parseRecordRoot() {
    val parsed =
        CconElement.decodeFromString(
            "${ControlChar.SOH}file_patch${ControlChar.RS}module.yaml${ControlChar.STX}${ControlChar.ETX}file_patch${ControlChar.EOT}",
        )

    assertEquals(
        expected =
            CconRecord(
                tagName = "file_patch",
                headerValues = listOf("module.yaml"),
                childElements = emptyList(),
            ),
        actual = parsed,
    )
  }

  @Test
  fun test_CconElement_decodeFromString_parseNestedHierarchy() {
    val encoded =
        CconRecord(
                tagName = "patch_set",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "file_patch",
                            headerValues = listOf("module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "replace",
                                        headerValues = listOf("2", "3"),
                                        childElements =
                                            listOf(
                                                CconString(content = "beta: 20"),
                                                CconString(content = "delta: 4"),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )
            .encodeToString()

    val parsed = CconElement.decodeFromString(encoded)

    assertEquals(
        expected =
            CconRecord(
                tagName = "patch_set",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "file_patch",
                            headerValues = listOf("module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "replace",
                                        headerValues = listOf("2", "3"),
                                        childElements =
                                            listOf(
                                                CconString(content = "beta: 20"),
                                                CconString(content = "delta: 4"),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ),
        actual = parsed,
    )
  }

  @Test
  fun test_CconElement_encodeToString_roundTripsNestedHierarchy() {
    val element =
        CconRecord(
            tagName = "body",
            headerValues = listOf("v1"),
            childElements =
                listOf(
                    CconString(content = "header"),
                    CconRecord(
                        tagName = "name",
                        headerValues = listOf("primary"),
                        childElements =
                            listOf(
                                CconString(content = "value"),
                                CconRecord(
                                    tagName = "empty",
                                    headerValues = emptyList(),
                                    childElements = emptyList(),
                                ),
                            ),
                    ),
                ),
        )

    val encoded = element.encodeToString()
    val decoded = CconElement.decodeFromString(encoded)

    assertEquals(expected = element, actual = decoded)
  }

  @Test
  fun test_CconElement_decodeFromString_rejectUnexpectedRootMarker() {
    val exception =
        assertThrows<CconParseException> { CconElement.decodeFromString("${ControlChar.HT}") }

    assertEquals("Unexpected character '${ControlChar.HT}'", exception.text)
    assertEquals(0, exception.offset)
  }

  @Test
  fun test_CconElement_decodeFromString_rejectTrailingContentAfterRecord() {
    val exception =
        assertThrows<CconParseException> {
          CconElement.decodeFromString(
              "${ControlChar.SOH}x${ControlChar.RS}${ControlChar.STX}${ControlChar.ETX}x${ControlChar.EOT}${ControlChar.SO}junk${ControlChar.SI}",
          )
        }

    assertEquals("Unexpected trailing character '${ControlChar.SO}'", exception.text)
    assertEquals(7, exception.offset)
  }

  @Test
  fun test_CconElement_decodeFromString_rejectRecordWithMismatchedClosingTag() {
    val exception =
        assertThrows<CconParseException> {
          CconElement.decodeFromString(
              "${ControlChar.SOH}open${ControlChar.RS}${ControlChar.STX}${ControlChar.ETX}close${ControlChar.EOT}",
          )
        }

    assertEquals("Expected closing tag 'open', but found 'close'", exception.text)
  }

  @Test
  fun test_CconElement_decodeFromString_rejectRecordMissingContentStartMarker() {
    val exception =
        assertThrows<CconParseException> {
          CconElement.decodeFromString(
              "${ControlChar.SOH}open${ControlChar.RS}header${ControlChar.SO}value${ControlChar.SI}",
          )
        }

    assertEquals(
        "Expected header value separator '${ControlChar.US}' or content start marker '${ControlChar.STX}', but found '${ControlChar.SO}'",
        exception.text,
    )
  }

  @Test
  fun test_CconElement_decodeFromString_rejectRecordMissingEndMarker() {
    val exception =
        assertThrows<CconParseException> {
          CconElement.decodeFromString(
              "${ControlChar.SOH}open${ControlChar.RS}${ControlChar.STX}${ControlChar.ETX}open",
          )
        }

    assertEquals("Expected end marker '${ControlChar.EOT}'", exception.text)
  }

  @Test
  fun test_CconElement_decodeFromString_rejectEmptyInput() {
    val exception = assertThrows<CconParseException> { CconElement.decodeFromString("") }

    assertEquals("Unexpected character 'null'", exception.text)
    assertEquals(0, exception.offset)
  }

  @Test
  fun test_CconElement_ebnfGrammarDescription_mentionsAllElementTypes() {
    assertTrue(CconElement.ebnfGrammarDescription.contains("element = record | string ;"))
    assertTrue(CconElement.ebnfGrammarDescription.contains("record ="))
    assertTrue(CconElement.ebnfGrammarDescription.contains("string ="))
  }
}

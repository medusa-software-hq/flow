package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.commons.serialization.ccon.CconElement
import software.medusa.commons.serialization.ccon.CconRecord
import software.medusa.commons.serialization.ccon.CconString
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.code.CodeBlock.LineIndexRange

internal data object CconAiCodePatcher_wire_utils {
  private const val inputFileTag = "input_file"
  private const val filePathField = "file_path"
  private const val recordsField = "records"
  private const val literalLineTag = "literal_line"
  private const val maskedLineTag = "masked_line"
  private const val lineIndexField = "line_index"
  private const val contentField = "content"
  private const val maskReasonField = "mask_reason"
  private const val patchSetTag = "patches"
  private const val filePatchTag = "patch"
  private const val insertBeforeTag = "insert_before"
  private const val insertAfterTag = "insert_after"
  private const val replaceTag = "replace"
  private const val deleteTag = "delete"

  data class BodyAstNode(
      val filePatches: List<FilePatchAstNode>,
  )

  data class FilePatchAstNode(
      val filePath: String,
      val patchFragments: List<PatchFragmentAstNode>,
  )

  sealed interface PatchFragmentAstNode {
    data class InsertBefore(
        val laterLineNumber: Int,
        val lines: List<String>,
    ) : PatchFragmentAstNode

    data class InsertAfter(
        val earlierLineNumber: Int,
        val lines: List<String>,
    ) : PatchFragmentAstNode

    data class Replace(
        val startLineNumber: Int,
        val endLineNumberInclusive: Int,
        val lines: List<String>,
    ) : PatchFragmentAstNode

    data class Delete(
        val startLineNumber: Int,
        val endLineNumberInclusive: Int,
    ) : PatchFragmentAstNode
  }

  val responseSchemaDescription: String =
      """
      # <foo [Header1, Header2]> Child+ </foo>
      # ...means a record with tag `foo`, header values Header1 and Header2, and child elements defined by Child.
      
      Body :: <$patchSetTag []> FilePatch* </$patchSetTag>
      
      # A patch for a file at path [FilePath] with the given fragments.
      FilePatch :: <$filePatchTag [FilePath]> PatchFragment+ </$filePatchTag>
      
      # A relative Unix-style file path
      FilePath :: CconString
      
      # A single patch fragment. Line numbers are 1-based and refer to the pre-patch state. Line ranges are inclusive on both ends.
      PatchFragment :: InsertBeforeFragment | InsertAfterFragment | ReplaceFragment | DeleteFragment
      
      # Insert the given lines before line with number [%d]
      InsertBeforeFragment :: <$insertBeforeTag ["%d"]> Line+ </$insertBeforeTag>

      # Insert the given lines after line with number [%d]
      InsertAfterFragment :: <$insertAfterTag ["%d"]> Line+ </$insertAfterTag>

      # Replace the lines with numbers [%d-%d] with the given lines
      ReplaceFragment :: <$replaceTag ["%d", "%d"]> Line+ </$replaceTag>
      
      # Delete the lines with numbers [%d-%d]
      DeleteFragment :: <$deleteTag ["%d", "%d"] />
      
      # A single line, consisting of non-control characters (except \t, which is allowed)
      Line :: CconString
      """
          .trimIndent()

  val ebnfResponseGrammarDescription: String =
      """
      body = patch_set ;

      patch_set = record(patch_set_tag, empty_header_values, { file_patch }) ;
      file_patch = record(file_patch_tag, file_patch_header_values, { patch_fragment }) ;

      patch_fragment = insert_before_fragment | insert_after_fragment | replace_fragment | delete_fragment ;

      insert_before_fragment = record(insert_before_tag, one_line_number, nonempty_lines) ;
      insert_after_fragment = record(insert_after_tag, one_line_number, nonempty_lines) ;
      replace_fragment = record(replace_tag, two_line_numbers, nonempty_lines) ;
      delete_fragment = record(delete_tag, two_line_numbers, no_children) ;

      nonempty_lines = line, { line } ;
      line = string ;

      empty_header_values = (* empty *) ;
      one_line_number = line_number ;
      two_line_numbers = line_number, header_value_separator, line_number ;
      file_patch_header_values = file_path ;

      patch_set_tag = "${patchSetTag}" ;
      file_patch_tag = "${filePatchTag}" ;
      insert_before_tag = "${insertBeforeTag}" ;
      insert_after_tag = "${insertAfterTag}" ;
      replace_tag = "${replaceTag}" ;
      delete_tag = "${deleteTag}" ;

      line_number = positive_decimal ;
      file_path = raw_text ;
      string = ccon_string ;

      header_value_separator = "${CconRecord.headerValueSeparatorMarkerChar}" ;

      (* Concrete record wire shape used by commons CCON: *)
      record(tag_name, header_values, child_elements) =
        "${CconRecord.startMarkerChar}",
        tag_name,
        "${CconRecord.headerStartMarkerChar}",
        header_values,
        "${CconRecord.contentStartMarkerChar}",
        child_elements,
        "${CconRecord.contentEndMarkerChar}",
        tag_name,
        "${CconRecord.endMarkerChar}" ;
      ccon_string = "${CconString.startMarkerChar}", raw_text, "${CconString.endMarkerChar}" ;

      positive_decimal = "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9", { digit } ;
      digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
      raw_text = { non_control_char | tab } ;
      tab = "\t" ;
      no_children = (* empty *) ;
      """
          .trimIndent()

  val examplePatchSetCconText: String =
      CconRecord(
              tagName = patchSetTag,
              headerValues = emptyList(),
              childElements =
                  listOf(
                      CconRecord(
                          tagName = filePatchTag,
                          headerValues = listOf("path/to/module.yaml"),
                          childElements =
                              listOf(
                                  CconRecord(
                                      tagName = insertBeforeTag,
                                      headerValues = listOf("1"),
                                      childElements =
                                          listOf(
                                              CconString("new line to insert before the first one"),
                                          ),
                                  ),
                                  CconRecord(
                                      tagName = replaceTag,
                                      headerValues = listOf("3", "5"),
                                      childElements =
                                          listOf(
                                              CconString("  mode: strict"),
                                              CconString("  enabled: true"),
                                          ),
                                  ),
                                  CconRecord(
                                      tagName = deleteTag,
                                      headerValues = listOf("10", "12"),
                                      childElements = emptyList(),
                                  ),
                              ),
                      ),
                  ),
          )
          .encodeToString()

  fun MaskedCodeCatalog.encodeToCconString(): String = toCconElement().encodeToString()

  private fun MaskedCodeCatalog.toCconElement(): CconRecord =
      CconRecord(
          tagName = patchSetTag,
          headerValues = emptyList(),
          childElements =
              maskedCodeFileContentByPath.entries
                  .sortedBy { (filePath, _) -> filePath.toUnixRelativePathString() }
                  .map { (filePath, maskedCodeFileContent) ->
                    CconRecord(
                        tagName = inputFileTag,
                        headerValues = listOf(filePath.toUnixRelativePathString()),
                        childElements = maskedCodeFileContent.toCconLineRecords(),
                    )
                  },
      )

  private fun MaskedCodeFileContent.toCconLineRecords(): List<CconElement> =
      codeFileContent.indexedLines
          .map { indexedLine ->
            val lineRange = LineIndexRange.of(startIndex = indexedLine.index, length = 1)

            when {
              mask.maskedLineRanges.any { it.overlaps(lineRange) } ->
                  CconRecord(
                      tagName = maskedLineTag,
                      headerValues = listOf("Masked line"),
                      childElements = emptyList(),
                  )

              else ->
                  CconRecord(
                      tagName = literalLineTag,
                      headerValues =
                          listOf(
                              indexedLine.index.indexOneBased.toString(),
                              indexedLine.line.content,
                          ),
                      childElements = emptyList(),
                  )
            }
          }
          .toList()

  fun parseChangeSet(
      responseText: String,
      maskedCodeCatalog: MaskedCodeCatalog,
  ): ChangeSet {
    val astNode = parseAst(responseText)

    return astNode.toChangeSet(maskedCodeCatalog = maskedCodeCatalog)
  }

  fun parseAst(
      responseText: String,
  ): BodyAstNode {
    val root = CconElement.decodeFromString(responseText)
    val rootRecord = root.requireRecord(context = "patch set root")

    require(rootRecord.tagName == patchSetTag) {
      "Expected patch set root tag '$patchSetTag', but found '${rootRecord.tagName}'"
    }

    return BodyAstNode(
        filePatches =
            rootRecord.childElements.mapIndexed { patchIndex, patchElement ->
              parseFilePatchAst(patchElement = patchElement, patchIndex = patchIndex)
            },
    )
  }

  private fun BodyAstNode.toChangeSet(
      maskedCodeCatalog: MaskedCodeCatalog,
  ): ChangeSet {
    val availableRelativePaths = maskedCodeCatalog.maskedCodeFileContentByPath.keys

    val patchByFilePath = buildMap {
      for ((patchIndex, filePatchAstNode) in filePatches.withIndex()) {
        val (filePath, patch) = filePatchAstNode.toChange()

        require(filePath in availableRelativePaths) {
          "Patch references file not present in masked code catalog: ${filePath.toUnixRelativePathString()}"
        }

        val previousValue = put(filePath, patch)

        require(previousValue == null) {
          "Duplicate patch for file path ${filePath.toUnixRelativePathString()}"
        }
      }
    }

    return ChangeSet(
        changeByFilePath = patchByFilePath,
    )
  }

  private fun parseFilePatchAst(
      patchElement: CconElement,
      patchIndex: Int,
  ): FilePatchAstNode {
    val patchRecord = patchElement.requireRecord(context = "patch[$patchIndex]")

    require(patchRecord.tagName == filePatchTag) {
      "Expected patch[$patchIndex] tag '$filePatchTag', but found '${patchRecord.tagName}'"
    }
    require(patchRecord.headerValues.size == 1) {
      "Expected patch[$patchIndex] to contain exactly one header value for file path"
    }

    return FilePatchAstNode(
        filePath = patchRecord.headerValues.single(),
        patchFragments =
            patchRecord.childElements.mapIndexed { fragmentIndex, fragmentElement ->
              parseFragmentAst(
                  fragmentElement = fragmentElement,
                  fragmentContext = "patch[$patchIndex].fragments[$fragmentIndex]",
              )
            },
    )
  }

  private fun FilePatchAstNode.toChange(): Pair<LiteralRelativeUnixPath, Change.Patch> {
    val filePath = filePath.toLiteralRelativeUnixPath()

    return filePath to
        Change.Patch(
            fragmentByOldLineIndexRange =
                patchFragments.associate { patchFragmentAstNode ->
                  patchFragmentAstNode.toChangeFragment()
                },
        )
  }

  private fun PatchFragmentAstNode.toChangeFragment(): Pair<LineIndexRange, Change.Patch.Fragment> =
      when (this) {
        is PatchFragmentAstNode.InsertBefore ->
            LineIndexRange.empty(startIndex = LineIndex.ofOneBased(laterLineNumber)) to
                Change.Patch.Fragment(
                    newCodeBlock = CodeBlock.of(lines),
                )

        is PatchFragmentAstNode.InsertAfter ->
            LineIndexRange.empty(startIndex = LineIndex.ofOneBased(earlierLineNumber).next) to
                Change.Patch.Fragment(
                    newCodeBlock = CodeBlock.of(lines),
                )

        is PatchFragmentAstNode.Replace ->
            LineIndexRange(
                startIndex = LineIndex.ofOneBased(startLineNumber),
                endIndexExclusive = LineIndex.ofOneBased(endLineNumberInclusive).next,
            ) to
                Change.Patch.Fragment(
                    newCodeBlock = CodeBlock.of(lines),
                )

        is PatchFragmentAstNode.Delete ->
            LineIndexRange(
                startIndex = LineIndex.ofOneBased(startLineNumber),
                endIndexExclusive = LineIndex.ofOneBased(endLineNumberInclusive).next,
            ) to Change.Patch.Fragment.Empty
      }

  private fun parseFragmentAst(
      fragmentElement: CconElement,
      fragmentContext: String,
  ): PatchFragmentAstNode {
    val fragmentRecord = fragmentElement.requireRecord(context = fragmentContext)

    return when (fragmentRecord.tagName) {
      deleteTag -> parseDeleteFragment(fragmentRecord, fragmentContext)
      insertBeforeTag,
      insertAfterTag,
      replaceTag -> parseNonDeleteFragment(fragmentRecord, fragmentContext)
      else ->
          throw IllegalArgumentException(
              "Unrecognized fragment tag '${fragmentRecord.tagName}' in $fragmentContext",
          )
    }
  }

  private fun parseNonDeleteFragment(
      fragmentRecord: CconRecord,
      fragmentContext: String,
  ): PatchFragmentAstNode {
    val lines =
        fragmentRecord.childElements.mapIndexed { lineIndex, lineElement ->
          lineElement
              .requireString(
                  context = "$fragmentContext content[$lineIndex]",
              )
              .content
        }

    require(lines.isNotEmpty()) {
      "Expected $fragmentContext to contain at least one replacement line"
    }

    return when (fragmentRecord.tagName) {
      insertBeforeTag -> {
        require(fragmentRecord.headerValues.size == 1) {
          "Expected $fragmentContext insert_before fragment to have 1 header value"
        }

        val laterLineNumber =
            fragmentRecord.headerValues[0].toInt(
                fieldName = "header[0]",
                context = fragmentContext,
            )

        PatchFragmentAstNode.InsertBefore(
            laterLineNumber = laterLineNumber,
            lines = lines,
        )
      }

      insertAfterTag -> {
        require(fragmentRecord.headerValues.size == 1) {
          "Expected $fragmentContext insert_after fragment to have 1 header value"
        }

        val earlierLineNumber =
            fragmentRecord.headerValues[0].toInt(
                fieldName = "header[0]",
                context = fragmentContext,
            )

        PatchFragmentAstNode.InsertAfter(
            earlierLineNumber = earlierLineNumber,
            lines = lines,
        )
      }

      replaceTag -> {
        require(fragmentRecord.headerValues.size == 2) {
          "Expected $fragmentContext replace fragment to have 2 header values"
        }

        val startLineNumber =
            fragmentRecord.headerValues[0].toInt(
                fieldName = "header[0]",
                context = fragmentContext,
            )

        val endLineNumberInclusive =
            fragmentRecord.headerValues[1].toInt(
                fieldName = "header[1]",
                context = fragmentContext,
            )

        PatchFragmentAstNode.Replace(
            startLineNumber = startLineNumber,
            endLineNumberInclusive = endLineNumberInclusive,
            lines = lines,
        )
      }

      else ->
          throw IllegalArgumentException(
              "Unrecognized fragment tag '${fragmentRecord.tagName}' in $fragmentContext",
          )
    }
  }

  private fun parseDeleteFragment(
      fragmentRecord: CconRecord,
      fragmentContext: String,
  ): PatchFragmentAstNode.Delete {
    require(fragmentRecord.headerValues.size == 2) {
      "Expected $fragmentContext delete fragment to have 2 header values"
    }
    require(fragmentRecord.childElements.isEmpty()) {
      "Expected $fragmentContext delete fragment to have no child elements"
    }

    val startLineNumber =
        fragmentRecord.headerValues[0].toInt(
            fieldName = "header[0]",
            context = fragmentContext,
        )

    val endLineNumberInclusive =
        fragmentRecord.headerValues[1].toInt(
            fieldName = "header[1]",
            context = fragmentContext,
        )

    return PatchFragmentAstNode.Delete(
        startLineNumber = startLineNumber,
        endLineNumberInclusive = endLineNumberInclusive,
    )
  }
}

private fun String.toLiteralRelativeUnixPath(): LiteralRelativeUnixPath =
    RelativeUnixPath.parse(this).toLiteral()
        ?: throw IllegalArgumentException(
            "Patch path must consist of literal path segments: $this",
        )

private fun String.toInt(
    fieldName: String,
    context: String,
): Int {
  val intValue =
      toIntOrNull()
          ?: throw IllegalArgumentException(
              "Expected $context.$fieldName to be a decimal, but found '$this'",
          )

  return intValue
}

private fun CconElement.requireRecord(
    context: String,
): CconRecord =
    this as? CconRecord ?: throw IllegalArgumentException("Expected $context to be a CCON record")

private fun CconElement.requireString(
    context: String,
): CconString =
    this as? CconString ?: throw IllegalArgumentException("Expected $context to be a CCON string")

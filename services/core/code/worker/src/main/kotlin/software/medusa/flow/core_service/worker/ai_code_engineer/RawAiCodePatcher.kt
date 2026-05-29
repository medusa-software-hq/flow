package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.relativizeAgainst
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.RawAiCodePatcher_inputStructure_utils.toRawMaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.raw_patchset.RawDeleteFragment
import software.medusa.flow.core_service.worker.code.raw_patchset.RawFilePatch
import software.medusa.flow.core_service.worker.code.raw_patchset.RawInsertAfterFragment
import software.medusa.flow.core_service.worker.code.raw_patchset.RawInsertBeforeFragment
import software.medusa.flow.core_service.worker.code.raw_patchset.RawPatchFragment
import software.medusa.flow.core_service.worker.code.raw_patchset.RawPatchSet
import software.medusa.flow.core_service.worker.code.raw_patchset.RawPatchSetParser
import software.medusa.flow.core_service.worker.code.raw_patchset.RawReplaceFragment
import software.medusa.flow.core_service.worker.code.raw_patchset.parse
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

private val patchingModel = OpenAiModel.GptMidi

class RawAiCodePatcher(
    private val openAiClient: OpenAiClient,
) : AiCodePatcher {
  companion object {
    private val examplePatchsetText =
        RawPatchSet(
                patches =
                    listOf(
                        RawFilePatch(
                            filePath =
                                LiteralAbsoluteUnixPath.of(
                                    UnixPath.Name.Literal("path"),
                                    UnixPath.Name.Literal("to"),
                                    UnixPath.Name.Literal("repo"),
                                    UnixPath.Name.Literal("file1.kt"),
                                ),
                            fragments =
                                listOf(
                                    RawInsertBeforeFragment(
                                        laterLineIndex = LineIndex.First,
                                        insertedLines =
                                            listOf(
                                                CodeBlock.Line("import foo.bar.baz"),
                                            ),
                                    ),
                                    RawReplaceFragment(
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(10),
                                            endIndexExclusive = LineIndex.ofOneBased(15),
                                        ),
                                        listOf(
                                            CodeBlock.Line("fun foo() {"),
                                            CodeBlock.Line("    println(\"Hello, world!\")"),
                                            CodeBlock.Line("}"),
                                        ),
                                    ),
                                    RawDeleteFragment(
                                        deletedLineIndexRange =
                                            LineIndexRange(
                                                startIndex = LineIndex.ofOneBased(20),
                                                endIndexExclusive = LineIndex.ofOneBased(25),
                                            ),
                                    ),
                                ),
                        ),
                        RawFilePatch(
                            filePath =
                                LiteralAbsoluteUnixPath.of(
                                    UnixPath.Name.Literal("path"),
                                    UnixPath.Name.Literal("to"),
                                    UnixPath.Name.Literal("repo"),
                                    UnixPath.Name.Literal("file2.kt"),
                                ),
                            fragments =
                                listOf(
                                    RawDeleteFragment(
                                        deletedLineIndexRange =
                                            LineIndexRange(
                                                startIndex = LineIndex.ofOneBased(5),
                                                endIndexExclusive = LineIndex.ofOneBased(7),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )
            .encodeToString()
  }

  override fun patchToCompleteTask(
      taskDescription: String,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generateChanges(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): ChangeSet =
            generateChangesViaAi(
                extraContextMessages =
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.System,
                            text = "Generate a patch set that completes the described coding task.",
                        ),
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = taskDescription,
                        ),
                    ),
                maskedCodeCatalog = maskedCodeCatalog,
            )
      }

  override fun patchToFixIssues(
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generateChanges(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): ChangeSet =
            generateChangesViaAi(
                extraContextMessages =
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.System,
                            text = "Generate a patch set that fixes the diagnosed code issues.",
                        ),
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text =
                                ProperAiCodePatcher_responseStructure_utils.buildIssueFixingPrompt(
                                    originalTaskDescription = originalTaskDescription,
                                    moduleDiagnosis = moduleDiagnosis,
                                ),
                        ),
                    ),
                maskedCodeCatalog = maskedCodeCatalog,
            )
      }

  private suspend fun generateChangesViaAi(
      extraContextMessages: List<OpenAiMessage>,
      maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
  ): ChangeSet {
    val completionInput =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text =
                            """
                              Input grammar:
                              
                              ${RawMaskedCodeCatalog.ebnfGrammarDescription}
                              
                              Expected response grammar:
                              
                              ${RawPatchSetParser.ebnfGrammarDescription}
                              
                              Build a sample PATCHSET to confirm understanding the grammar.
                            """
                                .trimIndent(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = examplePatchsetText,
                    ),
                ) +
                    extraContextMessages +
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = maskedCodeCatalog.toRawMaskedCodeCatalog().encodeToString(),
                        ),
                    ),
        )

    val completionText =
        openAiClient
            .createUnstructuredCompletion(
                request =
                    OpenAiClient.CompletionRequest(
                        input = completionInput,
                        model = patchingModel,
                    ),
            )
            .responseText

    val rawPatchSet = RawPatchSetParser.parse(input = completionText)

    val patchSet = rawPatchSet.toChangeSet(maskedCodeCatalog = maskedCodeCatalog)

    return patchSet
  }
}

private val rawPatchSetRootPath = RawMaskedCodeCatalog.repoPseudoRootPath

private fun RawPatchSet.toChangeSet(
    maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
): ChangeSet {
  val availableRelativePaths = maskedCodeCatalog.maskedCodeFileContentByPath.keys

  return ChangeSet(
      changeByFilePath =
          patches.associate { rawFilePatch ->
            val relativeFilePath =
                rawFilePatch.filePath.relativizeAgainst(rawPatchSetRootPath).also { relativePath ->
                  require(relativePath in availableRelativePaths) {
                    "Patch references file not present in masked code catalog: ${rawFilePatch.filePath.toUnixAbsolutePathString()}"
                  }
                }

            relativeFilePath to rawFilePatch.toChange()
          },
  )
}

private fun RawFilePatch.toChange(): Change.Patch =
    Change.Patch(
        fragmentByOldLineIndexRange =
            fragments.associate { rawFragment ->
              rawFragment.oldLineIndexRange to
                  Change.Patch.Fragment(
                      newCodeBlock = rawFragment.newCodeBlock,
                  )
            },
    )

private val RawPatchFragment.oldLineIndexRange: LineIndexRange
  get() =
      when (this) {
        is RawInsertBeforeFragment -> LineIndexRange.empty(startIndex = laterLineIndex)
        is RawInsertAfterFragment -> LineIndexRange.empty(startIndex = earlierLineIndex.next)
        is RawReplaceFragment -> replacedLineIndexRange
        is RawDeleteFragment -> deletedLineIndexRange
      }

private val RawPatchFragment.newCodeBlock: CodeBlock
  get() =
      when (this) {
        is RawInsertBeforeFragment -> CodeBlock(lines = insertedLines)
        is RawInsertAfterFragment -> CodeBlock(lines = insertedLines)
        is RawReplaceFragment -> CodeBlock(lines = replacementLines)
        is RawDeleteFragment -> CodeBlock.Empty
      }

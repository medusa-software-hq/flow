package software.medusa.flow.harness.assistance

import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeDirectory
import software.medusa.commons.git.worktree.GitWorktreeFile
import software.medusa.commons.openai_client.tools.OaiToolDefinition
import software.medusa.commons.openai_client.tools.OaiToolName
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.mutation.applyMutation
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedExposureMeter
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityDeletion
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * The real [HrsToolbox]: `expand_directory` / `open_file` / `expose_file` / `hide_file` go through
 * [VedWorktreeAdjustment] (reading [gitWorktree] where they need real content); `patch` goes
 * through [VedWorktreePatch] and mirrors the resulting mutation into [physicalRootDirectory] — the
 * same virtual/physical bridge [software.medusa.flow.harness.HrsProperTaskCompleter] uses for the
 * classic engine. `peek_file` reads [gitWorktree] directly and touches neither the virtual worktree
 * nor the physical one. `run_checks` drives [projectConnection]. `done` just unwraps its argument.
 *
 * Every worktree mutation this delegation makes is stamped at the single, constant
 * [delegationTimestamp] — the equal-timestamp collapse in
 * [software.medusa.flow.virtual_editor.worktree.VedOpenedFile.ContentVersionHistory.extend]
 * guarantees at most one net content version per file for the whole thread.
 *
 * Malformed calls (bad paths, edits on files that don't exist, JSON that doesn't match a tool's
 * schema, ...) never throw out of [execute]: every domain check below raises
 * [IllegalArgumentException] or [IllegalStateException] (the latter from the adjustment/patch
 * machinery itself), and both are caught here and turned into [HrsToolbox.ToolOutcome.Rejected].
 */
class HrsProperToolbox(
    private val gitWorktree: GitWorktree,
    private val physicalRootDirectory: UfsMutableDirectory,
    private val projectConnection: UnpProjectConnection,
    private val delegationTimestamp: VedTimestamp,
    private val softBudgetTokens: Int = defaultSoftBudgetTokens,
) : HrsToolbox {
  companion object {
    const val defaultSoftBudgetTokens = 20_000

    /** Truncation ceiling on a single module's diagnostic output in a `run_checks` result. */
    private const val maxDiagnosticChars = 4000

    const val expandDirectoryToolName = "expand_directory"
    const val peekFileToolName = "peek_file"
    const val openFileToolName = "open_file"
    const val patchToolName = "patch"
    const val exposeFileToolName = "expose_file"
    const val hideFileToolName = "hide_file"
    const val runChecksToolName = "run_checks"
    const val doneToolName = "done"

    /** The fixed tool definitions this toolbox always advertises — independent of any state. */
    val toolDefinitions: List<OaiToolDefinition> =
        listOf(
            tool(
                name = expandDirectoryToolName,
                description = "Expand a collapsed directory, listing its immediate children.",
                descriptor = HrsToolPathArgs.serializer().descriptor,
            ),
            tool(
                name = peekFileToolName,
                description =
                    "Show a file's current content without opening it. Nothing is remembered " +
                        "afterward — the file stays closed and this never joins the delegation record. " +
                        "Use it to check a file before deciding whether it's worth opening for real.",
                descriptor = HrsToolPathArgs.serializer().descriptor,
            ),
            tool(
                name = openFileToolName,
                description =
                    "Open a file for real: its settled content joins this delegation's record.",
                descriptor = HrsToolPathArgs.serializer().descriptor,
            ),
            tool(
                name = patchToolName,
                description =
                    "Edit, create, or delete files with their full new content (never a diff). " +
                        "Editing a currently-closed file opens it first, automatically.",
                descriptor = HrsToolPatchArgs.serializer().descriptor,
            ),
            tool(
                name = exposeFileToolName,
                description = "Put an already-open file's current content on the leader's board.",
                descriptor = HrsToolPathArgs.serializer().descriptor,
            ),
            tool(
                name = hideFileToolName,
                description = "Take an already-open file off the leader's board.",
                descriptor = HrsToolPathArgs.serializer().descriptor,
            ),
            tool(
                name = runChecksToolName,
                description =
                    "Run the project's analyze and test gate against the current worktree.",
                descriptor = HrsToolRunChecksArgs.serializer().descriptor,
            ),
            tool(
                name = doneToolName,
                description =
                    "End the delegation thread with a report. Call this once, and only once " +
                        "everything else is settled.",
                descriptor = HrsDelegationReport.serializer().descriptor,
            ),
        )

    private fun tool(
        name: String,
        description: String,
        descriptor: SerialDescriptor,
    ): OaiToolDefinition =
        OaiToolDefinition(
            name = OaiToolName(name),
            description = description,
            parameterSchema =
                SerializationClassJsonSchemaGenerator.Default.generateSchema(target = descriptor),
        )
  }

  override val toolDefinitions: List<OaiToolDefinition>
    get() = Companion.toolDefinitions

  override suspend fun execute(
      toolName: String,
      rawArguments: JsonElement,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome =
      try {
        when (toolName) {
          expandDirectoryToolName -> expandDirectory(decodePathArgs(rawArguments), worktree)
          peekFileToolName -> peekFile(decodePathArgs(rawArguments), worktree)
          openFileToolName -> openFile(decodePathArgs(rawArguments), worktree)
          patchToolName -> patch(decodePatchArgs(rawArguments), worktree)
          exposeFileToolName ->
              setExposure(
                  decodePathArgs(rawArguments),
                  worktree,
                  VedFileAdjustment.Expose,
                  VedExposure.Exposed,
                  "Exposed",
              )
          hideFileToolName ->
              setExposure(
                  decodePathArgs(rawArguments),
                  worktree,
                  VedFileAdjustment.Hide,
                  VedExposure.Hidden,
                  "Hid",
              )
          runChecksToolName -> runChecks(worktree)
          doneToolName -> HrsToolbox.ToolOutcome.Finished(report = decodeReport(rawArguments))
          else ->
              HrsToolbox.ToolOutcome.Rejected(
                  guidanceText =
                      "Unknown tool `$toolName`. Available tools: ${
                        toolDefinitions.joinToString { it.name.name }
                      }.",
              )
        }
      } catch (e: IllegalArgumentException) {
        HrsToolbox.ToolOutcome.Rejected(guidanceText = rejectionText(toolName, e))
      } catch (e: IllegalStateException) {
        HrsToolbox.ToolOutcome.Rejected(guidanceText = rejectionText(toolName, e))
      } catch (e: SerializationException) {
        HrsToolbox.ToolOutcome.Rejected(guidanceText = rejectionText(toolName, e))
      }

  private fun rejectionText(
      toolName: String,
      e: Exception,
  ): String = "`$toolName` failed: ${e.message ?: e::class.simpleName}"

  private fun decodePathArgs(
      rawArguments: JsonElement,
  ): HrsToolPathArgs = Json.decodeFromJsonElement(HrsToolPathArgs.serializer(), rawArguments)

  private fun decodePatchArgs(
      rawArguments: JsonElement,
  ): HrsToolPatchArgs = Json.decodeFromJsonElement(HrsToolPatchArgs.serializer(), rawArguments)

  private fun decodeReport(
      rawArguments: JsonElement,
  ): HrsDelegationReport =
      Json.decodeFromJsonElement(HrsDelegationReport.serializer(), rawArguments)

  // region expand_directory

  private suspend fun expandDirectory(
      args: HrsToolPathArgs,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome.Applied {
    // The model tends to write directory paths with a trailing slash (`/app/src/`), which would
    // otherwise parse into an empty final name.
    val names = parsePath(args.path.removeSuffix("/"))

    val adjustedWorktree =
        applyAdjustment(names = names, leaf = VedDirectoryAdjustment.Expand, worktree = worktree)

    val expandedDirectory =
        adjustedWorktree.findEntity(names) as? VedExpandedDirectory
            ?: error("`${args.path}` did not resolve to an expanded directory after expansion.")

    val childListing =
        expandedDirectory.labeledEntityByName.entries
            .sortedBy { (name, _) -> name.content }
            .joinToString(separator = "\n") { (name, labeledEntity) ->
              val kind = if (labeledEntity.entity is VedDirectory) "directory" else "file"
              "- `${name.content}` ($kind)"
            }
            .ifEmpty { "(empty)" }

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = adjustedWorktree,
        resultText = "Expanded `${args.path}`:\n$childListing",
    )
  }

  // endregion

  // region peek_file

  private suspend fun peekFile(
      args: HrsToolPathArgs,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome.Applied {
    val names = parsePath(args.path)

    val gitFile =
        findGitFile(names) ?: throw IllegalArgumentException("No file exists at `${args.path}`.")

    val content = readTextContent(gitFile = gitFile, path = args.path)

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = worktree, // Deliberately unchanged: peeking opens nothing, journals nothing.
        resultText =
            "Peeked `${args.path}` (not opened, not remembered):\n\n```\n${content.dump()}\n```",
    )
  }

  // endregion

  // region open_file

  private suspend fun openFile(
      args: HrsToolPathArgs,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome.Applied {
    val names = parsePath(args.path)

    val adjustedWorktree =
        applyAdjustment(names = names, leaf = VedFileAdjustment.Open, worktree = worktree)

    val opened =
        adjustedWorktree.findEntity(names) as? VedOpenedFile
            ?: error("`${args.path}` did not resolve to an opened file after opening.")

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = adjustedWorktree,
        resultText =
            "Opened `${args.path}` (t=${delegationTimestamp.t}):\n\n```\n${opened.currentContent.dump()}\n```",
    )
  }

  // endregion

  // region expose_file / hide_file

  private suspend fun setExposure(
      args: HrsToolPathArgs,
      worktree: VedWorktree,
      leaf: VedFileAdjustment,
      expectedExposure: VedExposure,
      verb: String,
  ): HrsToolbox.ToolOutcome.Applied {
    val names = parsePath(args.path)

    val adjustedWorktree = applyAdjustment(names = names, leaf = leaf, worktree = worktree)

    // A path with no existing entity at it (e.g. a typo) makes the underlying adjustment a silent
    // no-op rather than a thrown error — unlike expand_directory/open_file, exposure has no other
    // reason to inspect the result, so without this check a bad path would misreport success.
    val resultFile = adjustedWorktree.findEntity(names) as? VedOpenedFile
    if (resultFile == null || resultFile.exposure != expectedExposure) {
      error("`${args.path}` did not resolve to an opened file after $verb.")
    }

    val meter = VedExposureMeter.of(worktree = adjustedWorktree)

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = adjustedWorktree,
        resultText = "$verb `${args.path}`. ${meter.render(softBudgetTokens = softBudgetTokens)}",
    )
  }

  // endregion

  // region patch

  private suspend fun patch(
      args: HrsToolPatchArgs,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome.Applied {
    require(
        args.editedFiles.isNotEmpty() ||
            args.createdFiles.isNotEmpty() ||
            args.deletedFiles.isNotEmpty(),
    ) {
      "The patch is empty — include at least one edit, creation, or deletion."
    }

    val editNamesByPath = args.editedFiles.associate { it.path to parsePath(it.path) }

    val closedEditPaths = editNamesByPath.filterValues { names ->
      worktree.findEntity(names) is VedClosedFile
    }

    // `patch` implies open: silently open every currently-closed file it edits first, in one
    // adjustment round, before building the edit itself.
    val openedWorktree =
        if (closedEditPaths.isEmpty()) {
          worktree
        } else {
          applyAdjustment(
              entries = closedEditPaths.values.map { names -> names to VedFileAdjustment.Open },
              worktree = worktree,
          )
        }

    val patchEntries =
        buildPatchEntries(args = args, editNamesByPath = editNamesByPath, worktree = openedWorktree)

    val vedPatch = VedWorktreePatch(rootDirectoryPatch = buildPatchTree(entries = patchEntries))

    val patchResult =
        vedPatch.patchWorktree(worktree = openedWorktree, timestamp = delegationTimestamp)

    physicalRootDirectory.applyMutation(mutation = patchResult.rootDirectoryMutation)

    val touchedPaths =
        (args.editedFiles.map { it.path } +
                args.createdFiles.map { it.path } +
                args.deletedFiles.map { it.path })
            .sorted()

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = patchResult.patchedWorktree,
        resultText =
            "Patched (t=${delegationTimestamp.t}):\n" +
                touchedPaths.joinToString(separator = "\n") { path -> "- `$path`" },
    )
  }

  private fun buildPatchEntries(
      args: HrsToolPatchArgs,
      editNamesByPath: Map<String, List<UfsName.Literal>>,
      worktree: VedWorktree,
  ): List<Pair<List<UfsName.Literal>, VedEntityPatch>> {
    val edits =
        args.editedFiles.map { fileWrite ->
          val names = editNamesByPath.getValue(fileWrite.path)

          val existingFile =
              worktree.findEntity(names) as? VedOpenedFile
                  ?: throw IllegalArgumentException(
                      "Cannot edit `${fileWrite.path}`: no file could be opened at that path.",
                  )

          names to
              wholeFileReplacement(
                  oldLineCount = existingFile.currentContent.content.height,
                  newContent = fileWrite.newContent,
              )
        }

    val creations =
        args.createdFiles.map { fileWrite ->
          val names = parsePath(fileWrite.path)

          require(worktree.findEntity(names) == null) {
            "Cannot create `${fileWrite.path}`: an entity already exists at that path."
          }

          names to wholeFileReplacement(oldLineCount = 0, newContent = fileWrite.newContent)
        }

    val deletions =
        args.deletedFiles.map { filePath ->
          val names = parsePath(filePath.path)

          require(worktree.findEntity(names) is VedFile) {
            "Cannot delete `${filePath.path}`: no file exists at that path."
          }

          names to (VedEntityDeletion as VedEntityPatch)
        }

    return edits + creations + deletions
  }

  private fun wholeFileReplacement(
      oldLineCount: Int,
      newContent: String,
  ): VedFilePatch =
      VedFilePatch(
          txtPatch =
              TxtPatch(
                  fragmentByOldLineIndexRange =
                      mapOf(
                          TxtLineIndexRange.of(
                              startIndex = TxtLineIndex.First,
                              length = oldLineCount,
                          ) to TxtPatch.Fragment(newContent = TxtBlock.parse(newContent)),
                      ),
              ),
      )

  private fun buildPatchTree(
      entries: List<Pair<List<UfsName.Literal>, VedEntityPatch>>,
  ): VedDirectoryPatch {
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch> =
        entries
            .groupBy { (names, _) -> names.first() }
            .mapValues { (_, group) ->
              val leaf = group.firstOrNull { (names, _) -> names.size == 1 }

              leaf?.second
                  ?: buildPatchTree(
                      entries = group.map { (names, patchEntry) -> names.drop(1) to patchEntry },
                  )
            }

    return VedDirectoryPatch(childPatchByName = childPatchByName)
  }

  // endregion

  // region run_checks

  private suspend fun runChecks(
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome.Applied {
    val analyzeResult = projectConnection.analyzeAll()

    if (analyzeResult is JointResult.Failure) {
      return HrsToolbox.ToolOutcome.Applied(
          newWorktree = worktree,
          resultText = renderCheckFailure(stage = "Analysis", failure = analyzeResult),
      )
    }

    val testResult = projectConnection.testAll()

    if (testResult is JointResult.Failure) {
      return HrsToolbox.ToolOutcome.Applied(
          newWorktree = worktree,
          resultText = renderCheckFailure(stage = "Testing", failure = testResult),
      )
    }

    return HrsToolbox.ToolOutcome.Applied(
        newWorktree = worktree,
        resultText = "All checks passed (analyze + test).",
    )
  }

  private fun renderCheckFailure(
      stage: String,
      failure: JointResult.Failure,
  ): String = buildString {
    appendLine("$stage failed in ${failure.failureByModulePath.size} module(s):")
    failure.failureByModulePath.forEach { (modulePath, moduleFailure) ->
      appendLine()
      appendLine("`${modulePath.toUnixAbsolutePathString()}`:")
      appendLine("```")
      appendLine(moduleFailure.diagnosticOutput.take(maxDiagnosticChars))
      if (moduleFailure.diagnosticOutput.length > maxDiagnosticChars) appendLine("... (truncated)")
      appendLine("```")
    }
  }

  // endregion

  // region shared worktree/git helpers

  private suspend fun applyAdjustment(
      names: List<UfsName.Literal>,
      leaf: VedEntityAdjustment,
      worktree: VedWorktree,
  ): VedWorktree = applyAdjustment(entries = listOf(names to leaf), worktree = worktree)

  private suspend fun applyAdjustment(
      entries: List<Pair<List<UfsName.Literal>, VedEntityAdjustment>>,
      worktree: VedWorktree,
  ): VedWorktree {
    val adjustment = VedWorktreeAdjustment(rootDirectoryAdjustment = buildAdjustmentTree(entries))

    return adjustment
        .adjust(
            gitWorktree = gitWorktree,
            editorWorktree = worktree,
            timestamp = delegationTimestamp,
        )
        .adjustedWorktree
  }

  private fun buildAdjustmentTree(
      entries: List<Pair<List<UfsName.Literal>, VedEntityAdjustment>>,
  ): VedDirectoryAdjustment.Dive {
    val childAdjustmentByName: Map<UfsName.Literal, VedEntityAdjustment> =
        entries
            .groupBy { (names, _) -> names.first() }
            .mapValues { (_, group) ->
              val leaf = group.firstOrNull { (names, _) -> names.size == 1 }

              leaf?.second
                  ?: buildAdjustmentTree(
                      entries = group.map { (names, adjustment) -> names.drop(1) to adjustment },
                  )
            }

    return VedDirectoryAdjustment.Dive(childAdjustmentByName = childAdjustmentByName)
  }

  private fun VedWorktree.findEntity(
      names: List<UfsName.Literal>,
  ): VedEntity? {
    var current: VedEntity = rootDirectory

    names.forEach { name ->
      val directory = current as? VedExpandedDirectory ?: return null
      current = directory.labeledEntityByName[name]?.entity ?: return null
    }

    return current
  }

  private suspend fun findGitFile(
      names: List<UfsName.Literal>,
  ): GitWorktreeFile? {
    var currentDirectory: GitWorktreeDirectory = gitWorktree.rootDirectory

    names.forEachIndexed { index, name ->
      val child = currentDirectory.readChild(name = name) ?: return null

      if (index == names.lastIndex) return child as? GitWorktreeFile

      currentDirectory = child as? GitWorktreeDirectory ?: return null
    }

    return null
  }

  private suspend fun readTextContent(
      gitFile: GitWorktreeFile,
      path: String,
  ): TxtFileContent {
    val byteContent = gitFile.asFilesystemEntity.read()

    return TxtFileContent.decode(byteContent = byteContent)
        ?: throw IllegalArgumentException("Failed to decode `$path` as text.")
  }

  private fun parsePath(
      path: String,
  ): List<UfsName.Literal> {
    // The model is asked for absolute paths but occasionally drops the leading '/' — normalize
    // rather than reject the whole call over a formatting slip.
    val normalizedPath = if (path.startsWith("/")) path else "/$path"

    val literalPath =
        UfsAbsolutePath.parse(normalizedPath).toLiteral()
            ?: throw IllegalArgumentException("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to an entity." }

    return names
  }

  // endregion
}

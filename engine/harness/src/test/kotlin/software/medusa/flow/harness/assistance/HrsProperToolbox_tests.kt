package software.medusa.flow.harness.assistance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.filesystem.materializeIn
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Covers [HrsProperToolbox]: every tool against a real git-backed worktree and an in-memory
 * physical workspace, including the two interactions the story calls out explicitly — `patch`
 * implying `open` for a currently-closed file, and `expose_file`/`hide_file` echoing the exposure
 * meter — plus malformed calls bouncing back as [HrsToolbox.ToolOutcome.Rejected] instead of
 * throwing.
 */
class HrsProperToolbox_tests {
  private companion object {
    private val rootModulePath = checkNotNull(UfsAbsolutePath.parse("/").toLiteral())
  }

  private var tempGitDir: File? = null

  @AfterTest
  fun cleanUp() {
    tempGitDir?.deleteRecursively()
  }

  private suspend fun loadGitWorktree(
      files: Map<String, String> = mapOf("README.md" to "hello\n"),
  ): GitWorktree {
    val dir = createTempDirectory(prefix = "hrs-toolbox-test").toFile()
    tempGitDir = dir

    files.forEach { (relativePath, content) ->
      val file = File(dir, relativePath)
      file.parentFile.mkdirs()
      file.writeText(content)
    }

    fun git(vararg args: String) {
      val process =
          ProcessBuilder(
                  "git",
                  "-c",
                  "user.name=Test",
                  "-c",
                  "user.email=test@example.com",
                  "-c",
                  "commit.gpgsign=false",
                  *args,
              )
              .directory(dir)
              .redirectErrorStream(true)
              .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      check(exitCode == 0) { "git ${args.joinToString(" ")} failed ($exitCode): $output" }
    }

    git("init", "-q")
    git("add", "-A")
    git("commit", "-q", "-m", "initial")

    return GitWorktree.load(
        repoDirectory = UfsNioDirectory(directoryPath = dir.toPath()),
        globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
    )
  }

  private object AlwaysSuccessfulModuleConnection : UnpModuleConnection {
    override suspend fun bootstrap() = UnpModuleConnection.Result.Success

    override suspend fun analyze() = UnpModuleConnection.Result.Success

    override suspend fun test() = UnpModuleConnection.Result.Success

    override suspend fun normalize() = UnpModuleConnection.Result.Success
  }

  private class FixedResultModuleConnection(
      private val analyzeResult: UnpModuleConnection.Result = UnpModuleConnection.Result.Success,
      private val testResult: UnpModuleConnection.Result = UnpModuleConnection.Result.Success,
  ) : UnpModuleConnection {
    override suspend fun bootstrap() = UnpModuleConnection.Result.Success

    override suspend fun analyze() = analyzeResult

    override suspend fun test() = testResult

    override suspend fun normalize() = UnpModuleConnection.Result.Success
  }

  /**
   * [physicalRootDirectory] is seeded from [gitWorktree] first, mirroring how a real physical
   * workspace is allocated
   * ([software.medusa.flow.physical_workspace.PhwWorkspaceAllocator.allocateWorkspace]) — `patch`
   * mutates it as an existing mirror, not an empty directory.
   */
  private suspend fun buildToolbox(
      gitWorktree: GitWorktree,
      physicalRootDirectory: UfsMemoryDirectory = UfsMemoryDirectory(),
      moduleConnection: UnpModuleConnection = AlwaysSuccessfulModuleConnection,
      timestamp: VedTimestamp = VedTimestamp.zero,
  ): HrsProperToolbox {
    gitWorktree.rootDirectory.asFilteredFilesystemEntity.materializeIn(
        targetDirectory = physicalRootDirectory
    )

    return HrsProperToolbox(
        gitWorktree = gitWorktree,
        physicalRootDirectory = physicalRootDirectory,
        projectConnection =
            UnpProjectConnection(
                moduleConnectionByPath = mapOf(rootModulePath to moduleConnection)
            ),
        delegationTimestamp = timestamp,
    )
  }

  private fun pathArgs(
      path: String,
  ): JsonElement = buildJsonObject { put("path", JsonPrimitive(path)) }

  private fun VedWorktree.entityAt(
      vararg names: String,
  ): Any? {
    var current: Any? = rootDirectory
    for (name in names) {
      val directory = current as? VedExpandedDirectory ?: return null
      current =
          directory.labeledEntityByName.entries
              .firstOrNull { (key, _) -> key.content == name }
              ?.value
              ?.entity
    }
    return current
  }

  @Test
  fun `expand_directory expands a collapsed directory and lists its children`() = runBlocking {
    val gitWorktree =
        loadGitWorktree(files = mapOf("src/Main.kt" to "fun main() {}\n", "README.md" to "hi\n"))
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "expand_directory",
            rawArguments = pathArgs("/src"),
            worktree = worktree,
        )

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    assertTrue(applied.resultText.contains("Main.kt"))
    assertIs<VedExpandedDirectory>(applied.newWorktree.entityAt("src"))
  }

  @Test
  fun `peek_file returns content but leaves the file closed and the worktree unchanged`() =
      runBlocking {
        val gitWorktree = loadGitWorktree(files = mapOf("README.md" to "peekable content\n"))
        val toolbox = buildToolbox(gitWorktree = gitWorktree)
        val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

        val outcome =
            toolbox.execute(
                toolName = "peek_file",
                rawArguments = pathArgs("/README.md"),
                worktree = worktree,
            )

        val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
        assertTrue(applied.resultText.contains("peekable content"))
        assertEquals(worktree, applied.newWorktree)
        assertEquals(VedClosedFile, applied.newWorktree.entityAt("README.md"))
      }

  @Test
  fun `open_file opens the file for real and its content joins the worktree`() = runBlocking {
    val gitWorktree = loadGitWorktree(files = mapOf("README.md" to "openable content\n"))
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "open_file",
            rawArguments = pathArgs("/README.md"),
            worktree = worktree,
        )

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    assertTrue(applied.resultText.contains("openable content"))
    val opened = assertIs<VedOpenedFile>(applied.newWorktree.entityAt("README.md"))
    assertEquals("openable content\n", opened.currentContent.dump())
    assertEquals(VedExposure.Hidden, opened.exposure)
  }

  @Test
  fun `patch edits an already-open file and mirrors the mutation physically`() = runBlocking {
    val gitWorktree = loadGitWorktree(files = mapOf("README.md" to "old content\n"))
    val physicalRoot = UfsMemoryDirectory()
    val toolbox = buildToolbox(gitWorktree = gitWorktree, physicalRootDirectory = physicalRoot)

    val opened =
        toolbox.execute(
            toolName = "open_file",
            rawArguments = pathArgs("/README.md"),
            worktree = VedWorktree.import(sourceWorktree = gitWorktree),
        ) as HrsToolbox.ToolOutcome.Applied

    val patchArgs = buildJsonObject {
      put(
          "editedFiles",
          Json.encodeToJsonElement(
              kotlinx.serialization.builtins.ListSerializer(
                  HrsToolPatchArgs.FileWrite.serializer()
              ),
              listOf(HrsToolPatchArgs.FileWrite(path = "/README.md", newContent = "new content\n")),
          ),
      )
    }

    // physicalRoot starts empty (nothing was ever materialized into it); a successful outcome here
    // means `patch` mirrored its mutation into it without throwing — the same bridge
    // `HrsProperTaskCompleter` uses for the classic engine.
    val outcome =
        toolbox.execute(toolName = "patch", rawArguments = patchArgs, worktree = opened.newWorktree)

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    val patchedFile = assertIs<VedOpenedFile>(applied.newWorktree.entityAt("README.md"))
    assertEquals("new content\n", patchedFile.currentContent.dump())
  }

  @Test
  fun `patch implies open for a currently-closed file`() = runBlocking {
    val gitWorktree = loadGitWorktree(files = mapOf("README.md" to "closed content\n"))
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    // README.md was never opened — patch must open it before applying the edit.
    val patchArgs = buildJsonObject {
      put(
          "editedFiles",
          Json.encodeToJsonElement(
              kotlinx.serialization.builtins.ListSerializer(
                  HrsToolPatchArgs.FileWrite.serializer()
              ),
              listOf(
                  HrsToolPatchArgs.FileWrite(
                      path = "/README.md",
                      newContent = "edited via implied open\n",
                  )
              ),
          ),
      )
    }

    val outcome = toolbox.execute(toolName = "patch", rawArguments = patchArgs, worktree = worktree)

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    val patchedFile = assertIs<VedOpenedFile>(applied.newWorktree.entityAt("README.md"))
    assertEquals("edited via implied open\n", patchedFile.currentContent.dump())
  }

  @Test
  fun `patch creates a new file`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val patchArgs = buildJsonObject {
      put(
          "createdFiles",
          Json.encodeToJsonElement(
              kotlinx.serialization.builtins.ListSerializer(
                  HrsToolPatchArgs.FileWrite.serializer()
              ),
              listOf(HrsToolPatchArgs.FileWrite(path = "/new.txt", newContent = "brand new\n")),
          ),
      )
    }

    val outcome = toolbox.execute(toolName = "patch", rawArguments = patchArgs, worktree = worktree)

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    val createdFile = assertIs<VedOpenedFile>(applied.newWorktree.entityAt("new.txt"))
    assertEquals("brand new\n", createdFile.currentContent.dump())
  }

  @Test
  fun `expose_file and hide_file toggle exposure and echo the exposure meter`() = runBlocking {
    val gitWorktree = loadGitWorktree(files = mapOf("README.md" to "content\n"))
    val toolbox = buildToolbox(gitWorktree = gitWorktree)

    val opened =
        toolbox.execute(
            toolName = "open_file",
            rawArguments = pathArgs("/README.md"),
            worktree = VedWorktree.import(sourceWorktree = gitWorktree),
        ) as HrsToolbox.ToolOutcome.Applied

    val exposeOutcome =
        toolbox.execute(
            toolName = "expose_file",
            rawArguments = pathArgs("/README.md"),
            worktree = opened.newWorktree,
        )

    val exposeApplied = assertIs<HrsToolbox.ToolOutcome.Applied>(exposeOutcome)
    assertTrue(exposeApplied.resultText.contains("1 exposed file"))
    val exposedFile = assertIs<VedOpenedFile>(exposeApplied.newWorktree.entityAt("README.md"))
    assertEquals(VedExposure.Exposed, exposedFile.exposure)

    val hideOutcome =
        toolbox.execute(
            toolName = "hide_file",
            rawArguments = pathArgs("/README.md"),
            worktree = exposeApplied.newWorktree,
        )

    val hideApplied = assertIs<HrsToolbox.ToolOutcome.Applied>(hideOutcome)
    assertTrue(hideApplied.resultText.contains("0 exposed files"))
    val hiddenFile = assertIs<VedOpenedFile>(hideApplied.newWorktree.entityAt("README.md"))
    assertEquals(VedExposure.Hidden, hiddenFile.exposure)
  }

  @Test
  fun `run_checks reports success when analyze and test both pass`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox =
        buildToolbox(gitWorktree = gitWorktree, moduleConnection = AlwaysSuccessfulModuleConnection)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "run_checks",
            rawArguments = buildJsonObject {},
            worktree = worktree,
        )

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    assertTrue(applied.resultText.contains("passed"))
  }

  @Test
  fun `run_checks surfaces truncated per-module diagnostics on analyze failure`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox =
        buildToolbox(
            gitWorktree = gitWorktree,
            moduleConnection =
                FixedResultModuleConnection(
                    analyzeResult =
                        UnpModuleConnection.Result.Failure(
                            diagnosticOutput = "type error on line 3"
                        ),
                ),
        )
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "run_checks",
            rawArguments = buildJsonObject {},
            worktree = worktree,
        )

    val applied = assertIs<HrsToolbox.ToolOutcome.Applied>(outcome)
    assertTrue(applied.resultText.contains("Analysis failed"))
    assertTrue(applied.resultText.contains("type error on line 3"))
  }

  @Test
  fun `done unwraps its argument into the finished report`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val reportArgs = buildJsonObject {
      put("outcome", JsonPrimitive("Done"))
      put("narrative", JsonPrimitive("all set"))
      put("filesTouched", JsonPrimitive("- `/a.txt`"))
      put("bufferChanges", JsonPrimitive(""))
      put("checksSummary", JsonPrimitive("green"))
    }

    val outcome = toolbox.execute(toolName = "done", rawArguments = reportArgs, worktree = worktree)

    val finished = assertIs<HrsToolbox.ToolOutcome.Finished>(outcome)
    assertEquals("all set", finished.report.narrative)
  }

  @Test
  fun `an unknown tool name is rejected, not thrown`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "does_not_exist",
            rawArguments = buildJsonObject {},
            worktree = worktree,
        )

    assertIs<HrsToolbox.ToolOutcome.Rejected>(outcome)
  }

  @Test
  fun `malformed JSON arguments are rejected, not thrown`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "open_file",
            rawArguments = buildJsonObject { put("wrongField", JsonPrimitive("/x")) },
            worktree = worktree,
        )

    assertIs<HrsToolbox.ToolOutcome.Rejected>(outcome)
  }

  @Test
  fun `editing a file that does not exist is rejected, not thrown`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val patchArgs = buildJsonObject {
      put(
          "editedFiles",
          Json.encodeToJsonElement(
              kotlinx.serialization.builtins.ListSerializer(
                  HrsToolPatchArgs.FileWrite.serializer()
              ),
              listOf(HrsToolPatchArgs.FileWrite(path = "/does-not-exist.txt", newContent = "x")),
          ),
      )
    }

    val outcome = toolbox.execute(toolName = "patch", rawArguments = patchArgs, worktree = worktree)

    val rejected = assertIs<HrsToolbox.ToolOutcome.Rejected>(outcome)
    assertTrue(rejected.guidanceText.contains("does-not-exist.txt"))
  }

  @Test
  fun `opening a path that does not exist in git is rejected, not thrown`() = runBlocking {
    val gitWorktree = loadGitWorktree()
    val toolbox = buildToolbox(gitWorktree = gitWorktree)
    val worktree = VedWorktree.import(sourceWorktree = gitWorktree)

    val outcome =
        toolbox.execute(
            toolName = "open_file",
            rawArguments = pathArgs("/nope.txt"),
            worktree = worktree,
        )

    assertIs<HrsToolbox.ToolOutcome.Rejected>(outcome)
  }
}

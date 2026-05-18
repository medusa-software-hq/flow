package software.medusa.flow.lab

import java.nio.file.Path
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeMasker
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodePatcher
import software.medusa.openai_client.OpenAiClient

private const val apiKeyEnvVarName = "OPENAI_API_KEY"
private const val workingDirectoryPathEnvVarName = "WORKING_DIRECTORY_PATH"

suspend fun main() {
  val apiKey =
      System.getenv(apiKeyEnvVarName) ?: error("Environment variable $apiKeyEnvVarName is not set")

  val workingDirectoryPathString =
      System.getenv(workingDirectoryPathEnvVarName)
          ?: error("Environment variable $workingDirectoryPathEnvVarName is not set")

  val openAiClient =
      OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openAiBaseUrl,
                  apiKey = apiKey,
              ),
      )

  val aiCodeEditor =
      ProperAiCodeEditor(
          aiCodePatcher =
              ProperAiCodePatcher(
                  openAiClient = openAiClient,
              ),
          aiCodeMasker =
              ProperAiCodeMasker(
                  openAiClient = openAiClient,
              ),
      )

  val fileManipulator =
      aiCodeEditor.attemptToCompleteTask(
          relevantFilePaths =
              setOf(
                  RelativeUnixPath.of(
                      UnixPath.Name.Literal("src"),
                      UnixPath.Name.Literal("main"),
                      UnixPath.Name.Literal("kotlin"),
                      UnixPath.Name.Literal("software"),
                      UnixPath.Name.Literal("medusa"),
                      UnixPath.Name.Literal("flow"),
                      UnixPath.Name.Literal("core_service"),
                      UnixPath.Name.Literal("worker"),
                      UnixPath.Name.Literal("ai_code_engineer"),
                      UnixPath.Name.Literal("AiCodeEditor.kt"),
                  ),
              ),
          taskDescription =
              "Clean up this file and move it closer to a production-ready single-task editor flow.",
      )

  fileManipulator.editWithin(
      workingDirectory =
          NioCompatFsDirectory(
              directoryPath = Path.of(workingDirectoryPathString),
          ),
  )
}

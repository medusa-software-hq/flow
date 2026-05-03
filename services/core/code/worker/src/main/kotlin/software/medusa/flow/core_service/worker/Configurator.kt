package software.medusa.flow.core_service.worker

import java.nio.file.Path
import org.slf4j.LoggerFactory
import software.medusa.commons.process.ExecutableHandle
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueBack
import software.medusa.flow.db.FlowDatabase

private const val workingDirectoryPathEnvVarName = "WORKING_DIRECTORY_PATH"

class Configurator(
    private val flowDatabase: FlowDatabase,
    private val sessionExecutionJobQueueBack: SessionExecutionJobQueueBack,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(Configurator::class.java)
  }

  fun getFlowDatabase(): FlowDatabase = flowDatabase.also {
    logger.debug("Providing worker FlowDatabase {}", it)
  }

  fun getSessionExecutionJobQueueBack(): SessionExecutionJobQueueBack =
      sessionExecutionJobQueueBack.also {
        logger.debug("Providing worker SessionExecutionJobQueueBack {}", it)
      }

  fun getOpencodeExecutableHandle(): ExecutableHandle =
      ExecutableHandle.locate(commandName = "opencode")

  fun getWorkingDirectoryPath(): Path {
    val workingDirectoryPathStr =
        System.getenv(workingDirectoryPathEnvVarName)
            ?: error(
                "Environment variable $workingDirectoryPathEnvVarName is not set",
            )

    val workingDirectoryPath = Path.of(workingDirectoryPathStr)

    if (!workingDirectoryPath.isAbsolute) {
      throw IllegalArgumentException(
          "Expected an absolute path for working directory, but got: $workingDirectoryPath",
      )
    }

    return workingDirectoryPath
  }
}

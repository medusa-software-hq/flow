package software.medusa.flow.core_service.control

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueFront
import software.medusa.flow.db.FlowDatabase

private const val portEnvVarName = "PORT"
private const val corsAllowedOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"

class Configurator(
    private val flowDatabase: FlowDatabase,
    private val sessionExecutionJobQueueFront: SessionExecutionJobQueueFront,
    private val port: Int? = null,
    private val corsAllowedOriginRegex: String? = null,
) {
  fun getPort(): Int {
    val portStr = port?.toString() ?: System.getenv(portEnvVarName)
    requireNotNull(portStr) { "$portEnvVarName environment variable must be set" }

    logger.debug("Resolving control service port from value {}", portStr)

    return portStr.toIntOrNull()
        ?: error("$portEnvVarName environment variable must be a valid integer")
  }

  fun getCorsAllowedOriginRegex(): String =
      (corsAllowedOriginRegex
              ?: System.getenv(corsAllowedOriginRegexEnvVarName)
              ?: error("$corsAllowedOriginRegexEnvVarName environment variable must be set"))
          .also { logger.debug("Using CORS allowed origin regex {}", it) }

  fun getFlowDatabase(): FlowDatabase = flowDatabase

  fun getSessionExecutionJobQueueFront(): SessionExecutionJobQueueFront =
      sessionExecutionJobQueueFront

  companion object {
    private val logger = LoggerFactory.getLogger(Configurator::class.java)
  }
}

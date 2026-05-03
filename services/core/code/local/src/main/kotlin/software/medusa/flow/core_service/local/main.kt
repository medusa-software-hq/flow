package software.medusa.flow.core_service.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.control.Configurator as ControlConfigurator
import software.medusa.flow.core_service.control.runControlService
import software.medusa.flow.core_service.job_queue.InMemorySessionExecutionJobQueue
import software.medusa.flow.core_service.worker.Configurator as WorkerConfigurator
import software.medusa.flow.core_service.worker.runWorker
import software.medusa.flow.db.FlowDatabase

suspend fun main() = coroutineScope {
  logger.info("Starting local combined control+worker runtime")
  val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

  FlowDatabase.Schema.create(driver = driver)

  val flowDatabase = FlowDatabase(driver = driver)

  val inMemorySessionExecutionJobQueue = InMemorySessionExecutionJobQueue()
  logger.info("Initialized shared in-memory database and job queue")

  val controlServiceDeferred = async {
    runControlService(
        configurator =
            ControlConfigurator(
                flowDatabase = flowDatabase,
                sessionExecutionJobQueueFront = inMemorySessionExecutionJobQueue,
                port = 8080,
                corsAllowedOriginRegex = ".*",
            ),
    )
  }

  val workerDeferred = async {
    runWorker(
        configurator =
            WorkerConfigurator(
                flowDatabase = flowDatabase,
                sessionExecutionJobQueueBack = inMemorySessionExecutionJobQueue,
            ),
    )
  }

  controlServiceDeferred.await()
  workerDeferred.await()
}

private val logger = LoggerFactory.getLogger("software.medusa.flow.core_service.local.Main")

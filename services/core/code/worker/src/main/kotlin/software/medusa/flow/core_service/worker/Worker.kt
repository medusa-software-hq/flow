package software.medusa.flow.core_service.worker

import org.slf4j.LoggerFactory
import software.medusa.commons.network.ServerSocketPortAllocator
import software.medusa.commons.process.ProcessSpawner
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueBack
import software.medusa.flow.core_service.session.SessionExecutionService
import software.medusa.opencode_enclosed.EnclosedOpencodeSessionStarterImpl
import software.medusa.opencode_enclosed.LocalOpencodeServerSpawner
import software.medusa.opencode_enclosed.UuidPasswordGenerator

class Worker(
    private val sessionExecutionJobQueueBack: SessionExecutionJobQueueBack,
    private val sessionExecutionService: SessionExecutionService,
    private val workingSessionExecutor: WorkingSessionExecutor,
) {
  suspend fun runWork() {
    logger.info("Worker loop started")
    while (true) {
      val jobOffer = sessionExecutionJobQueueBack.waitForJob()

      logger.info("Worker picked up job for session {}", jobOffer.sessionId)

      sessionExecutionService.leaseSessionForExecution(
          sessionId = jobOffer.sessionId,
          sessionExecutor = workingSessionExecutor,
      )

      logger.info("Worker finished job for session {}", jobOffer.sessionId)
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(Worker::class.java)
  }
}

suspend fun runWorker(
    configurator: Configurator,
) {
  val processSpawner = ProcessSpawner.create(Runtime.getRuntime())

  val flowDatabase = configurator.getFlowDatabase()

  val sessionExecutionJobQueueBack = configurator.getSessionExecutionJobQueueBack()

  val opencodeExecutableHandle = configurator.getOpencodeExecutableHandle()

  val workingDirectoryPath = configurator.getWorkingDirectoryPath()

  val sessionExecutionService =
      SessionExecutionService(
          database = flowDatabase,
      )

  val localOpencodeServerSpawner =
      LocalOpencodeServerSpawner(
          processSpawner = processSpawner,
          opencodeExecutableHandle = opencodeExecutableHandle,
      )

  val opencodeSessionStarter =
      EnclosedOpencodeSessionStarterImpl(
          portAllocator = ServerSocketPortAllocator,
          localOpencodeServerSpawner = localOpencodeServerSpawner,
          passwordGenerator = UuidPasswordGenerator,
      )

  val taskExecutor =
      TaskExecutorImpl(
          opencodeSessionStarter = opencodeSessionStarter,
          workingDirectoryPath = workingDirectoryPath,
      )

  val workingSessionExecutor =
      WorkingSessionExecutor(
          taskExecutor = taskExecutor,
      )

  val worker =
      Worker(
          sessionExecutionJobQueueBack = sessionExecutionJobQueueBack,
          sessionExecutionService = sessionExecutionService,
          workingSessionExecutor = workingSessionExecutor,
      )

  worker.runWork()
}

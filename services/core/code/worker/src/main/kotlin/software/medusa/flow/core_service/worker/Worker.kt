package software.medusa.flow.core_service.worker

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueBack
import software.medusa.flow.core_service.session.SessionExecutionService

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
  val flowDatabase = configurator.getFlowDatabase()

  val sessionExecutionJobQueueBack = configurator.getSessionExecutionJobQueueBack()

  val worker =
      Worker(
          sessionExecutionJobQueueBack = sessionExecutionJobQueueBack,
          sessionExecutionService =
              SessionExecutionService(
                  database = flowDatabase,
              ),
          workingSessionExecutor =
              WorkingSessionExecutor(
                  taskExecutor = TaskExecutor(),
              ),
      )

  worker.runWork()
}

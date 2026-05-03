package software.medusa.flow.core_service.worker

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueBack
import software.medusa.flow.db.FlowDatabase

class Configurator(
    private val flowDatabase: FlowDatabase,
    private val sessionExecutionJobQueueBack: SessionExecutionJobQueueBack,
) {
  fun getFlowDatabase(): FlowDatabase = flowDatabase.also {
    logger.debug("Providing worker FlowDatabase {}", it)
  }

  fun getSessionExecutionJobQueueBack(): SessionExecutionJobQueueBack =
      sessionExecutionJobQueueBack.also {
        logger.debug("Providing worker SessionExecutionJobQueueBack {}", it)
      }

  companion object {
    private val logger = LoggerFactory.getLogger(Configurator::class.java)
  }
}

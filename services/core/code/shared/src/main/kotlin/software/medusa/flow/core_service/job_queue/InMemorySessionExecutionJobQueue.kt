package software.medusa.flow.core_service.job_queue

import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class InMemorySessionExecutionJobQueue :
    SessionExecutionJobQueueFront, SessionExecutionJobQueueBack {
  private val channel = Channel<SessionExecutionJobOffer>(capacity = Channel.UNLIMITED)

  override fun offerJob(sessionExecutionJobOffer: SessionExecutionJobOffer) {
    logger.info("Offering execution job for session {}", sessionExecutionJobOffer.sessionId)
    check(channel.trySend(sessionExecutionJobOffer).isSuccess) {
      "Failed to enqueue session execution job for ${sessionExecutionJobOffer.sessionId}"
    }
  }

  override suspend fun waitForJob(): SessionExecutionJobOffer {
    logger.debug("Waiting for next execution job")
    return channel.receive().also {
      logger.info("Dequeued execution job for session {}", it.sessionId)
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(InMemorySessionExecutionJobQueue::class.java)
  }
}

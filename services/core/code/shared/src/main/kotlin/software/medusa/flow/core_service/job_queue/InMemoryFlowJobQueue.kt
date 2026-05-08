package software.medusa.flow.core_service.job_queue

import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class InMemoryFlowJobQueue : FlowJobQueueFront, FlowJobQueueBack {
  companion object {
    private val logger = LoggerFactory.getLogger(InMemoryFlowJobQueue::class.java)
  }

  private val channel = Channel<FlowJobOffer>(capacity = Channel.UNLIMITED)

  override fun offerJob(flowJobOffer: FlowJobOffer) {
    logger.info("Offering execution job for flow {}", flowJobOffer.flowId)

    check(channel.trySend(flowJobOffer).isSuccess) {
      "Failed to enqueue flow execution job for ${flowJobOffer.flowId}"
    }
  }

  override suspend fun waitForJob(): FlowJobOffer {
    logger.debug("Waiting for next execution job")

    return channel.receive().also {
      logger.info("Dequeued execution job for flow {}", it.flowId)
    }
  }
}

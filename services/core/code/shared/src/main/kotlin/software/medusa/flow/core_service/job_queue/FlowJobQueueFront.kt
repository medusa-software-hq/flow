package software.medusa.flow.core_service.job_queue

interface FlowJobQueueFront {
  fun offerJob(
      flowJobOffer: FlowJobOffer,
  )
}

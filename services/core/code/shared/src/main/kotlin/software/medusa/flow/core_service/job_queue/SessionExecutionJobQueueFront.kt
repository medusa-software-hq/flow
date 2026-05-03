package software.medusa.flow.core_service.job_queue

interface SessionExecutionJobQueueFront {
  fun offerJob(
      sessionExecutionJobOffer: SessionExecutionJobOffer,
  )
}

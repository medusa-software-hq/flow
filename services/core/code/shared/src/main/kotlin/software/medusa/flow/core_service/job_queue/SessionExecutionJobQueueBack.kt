package software.medusa.flow.core_service.job_queue

interface SessionExecutionJobQueueBack {
  suspend fun waitForJob(): SessionExecutionJobOffer
}

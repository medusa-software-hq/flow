package software.medusa.flow.core_service.job_queue

interface FlowJobQueueBack {
  suspend fun waitForJob(): FlowJobOffer
}

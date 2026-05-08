package software.medusa.flow.core_service.worker

interface FlowTaskExecutorPreparator {
  fun prepareExecutor(): FlowExecutor
}

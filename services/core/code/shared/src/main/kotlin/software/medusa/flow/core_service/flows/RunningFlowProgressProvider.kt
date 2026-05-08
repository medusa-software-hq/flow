package software.medusa.flow.core_service.flows

interface RunningFlowProgressProvider {
  fun getRunningFlowProgress(
      flowId: FlowId,
  ): RunningFlowProgress?
}

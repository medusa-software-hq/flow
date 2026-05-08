package software.medusa.flow.core_service.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.flows.FlowBlueprint
import software.medusa.flow.core_service.flows.FlowId
import software.medusa.flow.core_service.flows.storage.FlowStore
import software.medusa.flow.core_service.worker.FlowExecutor.EnvironmentContext

class FlowRunner(
    private val coroutineScope: CoroutineScope,
    private val flowExecutor: FlowExecutor,
    private val flowStore: FlowStore,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(FlowRunner::class.java)
  }

  fun runFlow(
      flowId: FlowId,
  ) {
    coroutineScope.launch {
      val leaseFlowResult =
          flowStore.leaseFlow(
              flowId = flowId,
              processor =
                  object : FlowStore.LeasedFlowProcessor {
                    override suspend fun processLeasedFlow(
                        flowBlueprint: FlowBlueprint,
                        leasedFlowStore: FlowStore.LeasedFlowStore,
                    ) {
                      logger.info("Flow {} lease acquired, starting execution", flowId.raw)

                      val executableDag = flowBlueprint.compile()

                      val environmentContext =
                          object : FlowExecutor.EnvironmentContext {
                            override val taskResultRestorer = leasedFlowStore

                            override val taskProgressSaver = leasedFlowStore
                          }

                      val baselineContext =
                          with(environmentContext) { this@FlowRunner.flowExecutor.initializeFlow() }

                      with(
                          object :
                              FlowExecutionContext,
                              EnvironmentContext by environmentContext,
                              FlowExecutor.BaselineContext by baselineContext {
                            override val flowExecutor: FlowExecutor = this@FlowRunner.flowExecutor
                          },
                      ) {
                        executableDag.execute()
                      }
                    }
                  },
          )

      when (leaseFlowResult) {
        FlowStore.LeaseFlowResult.Processed -> {
          logger.info("Flow {} run successfully", flowId.raw)
        }

        FlowStore.LeaseFlowResult.Denied -> {
          logger.warn("Flow {} lease denied, skipping", flowId.raw)
        }
      }
    }
  }
}

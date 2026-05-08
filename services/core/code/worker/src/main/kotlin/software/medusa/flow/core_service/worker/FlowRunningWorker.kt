package software.medusa.flow.core_service.worker

import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import software.medusa.commons.network.ServerSocketPortAllocator
import software.medusa.commons.process.ProcessSpawner
import software.medusa.flow.core_service.flows.storage.FlowStore
import software.medusa.flow.core_service.job_queue.FlowJobQueueBack
import software.medusa.git.GitPersonalDetails
import software.medusa.git.GitRepository
import software.medusa.opencode_enclosed.EnclosedOpencodeSessionStarterImpl
import software.medusa.opencode_enclosed.LocalOpencodeServerSpawner
import software.medusa.opencode_enclosed.UuidPasswordGenerator

private val gitPersonalDetails =
    GitPersonalDetails(
        name = "Flow",
        email = "flow@medusa.software",
    )

class FlowRunningWorker(
    private val flowJobQueueBack: FlowJobQueueBack,
    private val flowRunner: FlowRunner,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(FlowRunningWorker::class.java)
  }

  suspend fun runWork() {
    logger.info("Work loop started")

    while (true) {
      val jobOffer = flowJobQueueBack.waitForJob()

      logger.info("Picked up job to run flow {}", jobOffer.flowId)

      flowRunner.runFlow(
          flowId = jobOffer.flowId,
      )
    }
  }
}

suspend fun runWorker(
    configurator: Configurator,
) {
  coroutineScope {
    val processSpawner = ProcessSpawner.create(Runtime.getRuntime())

    val flowDatabase = configurator.getFlowDatabase()

    val flowJobQueueBack = configurator.getFlowJobQueueBack()

    val opencodeExecutableHandle = configurator.getOpencodeExecutableHandle()

    val workingDirectoryPath = configurator.getWorkingDirectoryPath()

    val flowStore = FlowStore(flowDatabase = flowDatabase)

    val localOpencodeServerSpawner =
        LocalOpencodeServerSpawner(
            processSpawner = processSpawner,
            opencodeExecutableHandle = opencodeExecutableHandle,
        )

    val opencodeSessionStarter =
        EnclosedOpencodeSessionStarterImpl(
            portAllocator = ServerSocketPortAllocator,
            localOpencodeServerSpawner = localOpencodeServerSpawner,
            passwordGenerator = UuidPasswordGenerator,
        )

    val gitRepository = GitRepository.open(path = workingDirectoryPath)

    val properFlowTaskExecutor =
        ProperFlowExecutor(
            gitRepository = gitRepository,
            gitPersonalDetails = gitPersonalDetails,
            opencodeSessionStarter = opencodeSessionStarter,
        )

    val idempotentFlowTaskExecutor =
        IdempotentFlowExecutor(
            properTaskExecutor = properFlowTaskExecutor,
        )

    val flowRunner =
        FlowRunner(
            coroutineScope = this,
            flowExecutor = idempotentFlowTaskExecutor,
            flowStore = flowStore,
        )

    val flowRunningWorker =
        FlowRunningWorker(
            flowJobQueueBack = flowJobQueueBack,
            flowRunner = flowRunner,
        )

    flowRunningWorker.runWork()
  }
}

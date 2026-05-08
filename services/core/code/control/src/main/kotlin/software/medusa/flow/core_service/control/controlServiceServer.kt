package software.medusa.flow.core_service.control

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import com.linecorp.armeria.server.logging.LoggingService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.flows.FlowManagementService
import software.medusa.flow.core_service.flows.storage.FlowStore

suspend fun runControlService(
    configurator: Configurator,
) {
  val port = configurator.getPort()
  val originRegex = configurator.getCorsAllowedOriginRegex()

  logger.info("Starting control service with port={} corsAllowedOriginRegex={}", port, originRegex)

  val singleThreadExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "armeria-single-thread")
  }

  val coroutineDispatcher = singleThreadExecutor.asCoroutineDispatcher()

  val corsDecorator =
      CorsService.builderForOriginRegex(originRegex)
          .apply {
            allowRequestMethods(
                HttpMethod.POST,
                HttpMethod.OPTIONS,
            )

            allowRequestHeaders(
                HttpHeaderNames.AUTHORIZATION,
                HttpHeaderNames.CONTENT_TYPE,
                GrpcHeaderNames.X_GRPC_WEB,
                GrpcHeaderNames.X_USER_AGENT,
                GrpcHeaderNames.GRPC_TIMEOUT,
                GrpcHeaderNames.CONNECT_PROTOCOL_VERSION,
                GrpcHeaderNames.CONNECT_TIMEOUT_MS,
            )

            exposeHeaders(
                HttpHeaderNames.CONTENT_TYPE,
                GrpcHeaderNames.GRPC_STATUS,
                GrpcHeaderNames.GRPC_MESSAGE,
            )
          }
          .newDecorator()

  val flowDatabase = configurator.getFlowDatabase()
  logger.debug("Control service obtained FlowDatabase instance {}", flowDatabase)

  val sessionExecutionJobQueueFront = configurator.getSessionExecutionJobQueueFront()
  logger.debug(
      "Control service obtained FlowJobQueueFront {}",
      sessionExecutionJobQueueFront,
  )

  val flowManagementService =
      FlowManagementService(
          database = flowDatabase,
          flowJobQueueFront = sessionExecutionJobQueueFront,
      )

  val flowStore = FlowStore(flowDatabase = flowDatabase)

  val grpcService =
      GrpcService.builder()
          .apply {
            addService(
                CoreServiceGrpcImpl(
                    coroutineDispatcher = coroutineDispatcher,
                    sessionControlService = flowManagementService,
                    runningFlowProgressProvider = flowStore,
                ),
            )
          }
          .build()

  val server =
      Server.builder()
          .apply {
            http(port)

            service("/health", HealthCheckService.of())

            serviceUnder(
                "/",
                grpcService.decorate(LoggingService.newDecorator()).decorate(corsDecorator),
            )
          }
          .build()

  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            logger.info("Stopping control service")
            server.stop().join()
            coroutineDispatcher.close()
            singleThreadExecutor.shutdown()
          },
      )

  server.start().await()
  logger.info("Control service started on port {}", port)
}

private val logger = LoggerFactory.getLogger("software.medusa.flow.core_service.control.Server")

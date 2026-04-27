package software.medusa.flow.core_service

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await

suspend fun runServer(
    configurator: Configurator,
) {
  val port = configurator.getPort()
  val originRegex = configurator.getCorsAllowedOriginRegex()

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

  val grpcService =
      GrpcService.builder()
          .apply {
            addService(
                CoreServiceGrpcImpl(
                    coroutineDispatcher = coroutineDispatcher,
                ),
            )
          }
          .build()

  val server =
      Server.builder()
          .apply {
            http(port)

            service("/health", HealthCheckService.of())

            serviceUnder("/", grpcService.decorate(corsDecorator))
          }
          .build()

  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            server.stop().join()
            coroutineDispatcher.close()
            singleThreadExecutor.shutdown()
          },
      )

  server.start().await()
}

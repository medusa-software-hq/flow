package software.medusa.flow.core_service.control

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import software.medusa.flow.core_service.session.SessionManagementService
import software.medusa.flow.db.FlowDatabase

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

  val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

  FlowDatabase.Schema.create(driver = driver)

  val database = FlowDatabase(driver = driver)

  val sessionControlService =
      SessionManagementService(
          database = database,
      )

  val grpcService =
      GrpcService.builder()
          .apply {
            addService(
                CoreServiceGrpcImpl(
                    coroutineDispatcher = coroutineDispatcher,
                    sessionControlService = sessionControlService,
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
            server.stop().join()
            coroutineDispatcher.close()
            singleThreadExecutor.shutdown()
          },
      )

  server.start().await()
}

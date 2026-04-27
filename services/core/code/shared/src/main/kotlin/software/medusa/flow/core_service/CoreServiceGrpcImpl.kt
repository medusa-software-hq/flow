package software.medusa.flow.core_service

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import software.medusa.grpc.flow.core_service.v1.CoreServiceGrpcKt
import software.medusa.grpc.flow.core_service.v1.GreetRequest
import software.medusa.grpc.flow.core_service.v1.GreetResponse
import software.medusa.grpc.flow.core_service.v1.greetResponse

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
) : CoreServiceGrpcKt.CoreServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun greet(
      request: GreetRequest,
  ): GreetResponse {
    val name = request.name.ifBlank { "...stranger" }

    return greetResponse { greeting = "Hello, $name" }
  }
}

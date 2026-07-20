package software.medusa.flow.server

import software.medusa.flow.v1.CounterServiceGrpcKt
import software.medusa.flow.v1.DecrementRequest
import software.medusa.flow.v1.DecrementResponse
import software.medusa.flow.v1.GetCountRequest
import software.medusa.flow.v1.GetCountResponse
import software.medusa.flow.v1.IncrementRequest
import software.medusa.flow.v1.IncrementResponse

class CounterServiceImpl(
    private val counterStore: CounterStore,
) : CounterServiceGrpcKt.CounterServiceCoroutineImplBase() {
  override suspend fun getCount(request: GetCountRequest): GetCountResponse =
      GetCountResponse.newBuilder().setCount(counterStore.getCount(mainCounterId)).build()

  override suspend fun increment(request: IncrementRequest): IncrementResponse =
      IncrementResponse.newBuilder()
          .setCount(counterStore.incrementAndGetCount(mainCounterId))
          .build()

  override suspend fun decrement(request: DecrementRequest): DecrementResponse =
      DecrementResponse.newBuilder()
          .setCount(counterStore.decrementAndGetCount(mainCounterId))
          .build()
}

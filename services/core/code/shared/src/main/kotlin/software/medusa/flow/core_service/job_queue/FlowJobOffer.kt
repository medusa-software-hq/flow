package software.medusa.flow.core_service.job_queue

import software.medusa.flow.core_service.flows.FlowId

data class FlowJobOffer(
    val flowId: FlowId,
)

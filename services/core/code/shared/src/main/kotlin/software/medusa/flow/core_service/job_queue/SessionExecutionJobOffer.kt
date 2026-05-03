package software.medusa.flow.core_service.job_queue

import software.medusa.flow.core_service.session.SessionId

data class SessionExecutionJobOffer(
    val sessionId: SessionId,
)

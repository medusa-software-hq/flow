package software.medusa.flow.harness.ai_system

/**
 * Thrown when the model returns a response with no usable text content — no choices, or a choice
 * whose message content is null — and retrying failed to get a usable one.
 *
 * This is a transient, provider-side condition (observed with DeepSeek via OpenRouter: the model
 * emits only reasoning tokens, or hits its output cap mid-reasoning, and never produces the final
 * `content`; provider routing makes it nondeterministic), not a bug in the request.
 * [HrsRetryingAiClient] retries it a bounded number of times and raises this once the budget is
 * spent — a typed, readable failure rather than a bare `IllegalStateException` from deep in the
 * OpenAI client.
 */
class HrsEmptyAiResponseException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

package software.medusa.flow.server

import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey

/**
 * Carries the verified caller's email from the auth decorator to the service methods.
 *
 * The auth decorator (see [GoogleIdTokenAuthDecorator] / [NoOpAuthDecorator]) runs before the gRPC
 * handler and stores the email as a [ServiceRequestContext] attribute; services read it back with
 * [currentEmail]. Armeria propagates the request context into the coroutine handler, so this works
 * inside the suspend service methods.
 */
object AuthenticatedUser {
  val emailAttributeKey: AttributeKey<String> =
      AttributeKey.valueOf("software.medusa.flow.server.authenticatedEmail")

  /** The verified caller email for the in-flight request, or null when there is no request/attr. */
  fun currentEmail(): String? = ServiceRequestContext.currentOrNull()?.attr(emailAttributeKey)
}

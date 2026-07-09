package software.medusa.flow.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext

/** Passes every request through without any authentication check. For local development only. */
object NoOpAuthDecorator : DecoratingHttpServiceFunction {
  private const val localDevEmail = "local@localhost"

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    // Stamp a placeholder identity so `created_by` is populated in local runs.
    ctx.setAttr(AuthenticatedUser.emailAttributeKey, localDevEmail)

    return delegate.serve(ctx, req)
  }
}

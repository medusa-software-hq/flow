package software.medusa.flow.systemtest

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Plain HTTP probes for the checks that aren't gRPC — the deployment's health endpoint, the GitHub
 * webhook route, and the SPA. Mirrors `smoke.sh`'s `curl` calls: no redirect following (so an
 * IAP-gated SPA reads as its raw 302, not the login page it points at), a short timeout, and status
 * codes returned rather than bodies.
 */
object StagingHttp {
  private val client: HttpClient =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build()

  /** GET [url], returning the HTTP status code. */
  fun getStatus(
      url: String,
  ): Int =
      client
          .send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
              HttpResponse.BodyHandlers.discarding(),
          )
          .statusCode()

  /** POST [body] to [url] with [headers], returning the HTTP status code. */
  fun postStatus(
      url: String,
      headers: Map<String, String>,
      body: String,
  ): Int {
    val builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.forEach { (name, value) -> builder.header(name, value) }
    return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
  }
}

package software.medusa.flow.core_service.control

private const val portEnvVarName = "PORT"
private const val corsAllowedOriginRegexEnvVarName = "CORS_ALLOWED_ORIGIN_REGEX"

class Configurator {
  fun getPort(): Int {
    val portStr =
        System.getenv(portEnvVarName) ?: error("$portEnvVarName environment variable must be set")

    return portStr.toIntOrNull()
        ?: error("$portEnvVarName environment variable must be a valid integer")
  }

  fun getCorsAllowedOriginRegex(): String =
      System.getenv(corsAllowedOriginRegexEnvVarName)
          ?: error("$corsAllowedOriginRegexEnvVarName environment variable must be set")
}

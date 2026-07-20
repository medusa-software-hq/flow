plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

// A leaf module: the GitHub App JWT → installation-token minting, shared by the backend's REST
// clients and the worker (which mints its own per-session token). No proto, no gRPC, no DB.
dependencies {
  api(platform(libs.armeria.bom))
  api(libs.armeria.kotlin)
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)
  api(libs.nimbus.jose.jwt)

  testImplementation(libs.kotlin.test)
  testImplementation(project(":test-fixtures:github-stub"))
}

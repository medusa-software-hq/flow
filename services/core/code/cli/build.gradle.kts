plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
  implementation(project(":commons"))
  implementation(project(":openai-client"))
  implementation(project(":worker"))

  implementation(libs.clikt)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

application { mainClass = "software.medusa.flow.cli.MainKt" }

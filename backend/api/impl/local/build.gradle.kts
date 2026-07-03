plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies { implementation(project(":shared")) }

application { mainClass = "software.medusa.flow.server.MainKt" }

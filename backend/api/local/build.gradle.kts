plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies { implementation(project(":backend:api:shared")) }

application { mainClass = "software.medusa.flow.server.MainKt" }

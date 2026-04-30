plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

val mainClassFqn = "software.medusa.flow.core_service.local.MainKt"

dependencies { implementation(project(":control")) }

application { mainClass = mainClassFqn }

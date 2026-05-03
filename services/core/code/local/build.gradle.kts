plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

val mainClassFqn = "software.medusa.flow.core_service.local.MainKt"

dependencies { implementation(project(":control")) }

dependencies { implementation(project(":worker")) }

dependencies { implementation(project(":shared")) }

dependencies { implementation(libs.sqldelight.sqlite.driver) }

application { mainClass = mainClassFqn }

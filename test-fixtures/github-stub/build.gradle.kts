plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(platform(libs.armeria.bom))
  api(libs.armeria.kotlin)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showCauses = true
    showStackTraces = true
  }
}

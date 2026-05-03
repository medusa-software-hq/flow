plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)

  `java-library`
}

dependencies {
  api(platform(libs.armeria.bom))

  api(libs.armeria.kotlin)
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

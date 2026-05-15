plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.io.bytestring)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

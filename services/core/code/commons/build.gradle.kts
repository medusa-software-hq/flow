plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)

  `java-library`
}

dependencies {
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.io.bytestring)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

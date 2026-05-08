plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))

  implementation(libs.jgit)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.io.core)
  testImplementation(libs.kotlinx.io.bytestring)
}

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

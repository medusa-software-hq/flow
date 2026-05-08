plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))

  implementation(libs.jgit)
  implementation(libs.kotlinx.io.bytestring)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.io.core)
}

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  implementation(project(":commons"))
  implementation(project(":shared"))
  implementation(project(":opencode-enclosed"))

  implementation(libs.logback.classic)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

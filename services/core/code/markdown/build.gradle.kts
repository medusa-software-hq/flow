plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))
  implementation(libs.commonmark)
  implementation(libs.commonmark.ext.cc)

  testImplementation(libs.kotlin.test)
}

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

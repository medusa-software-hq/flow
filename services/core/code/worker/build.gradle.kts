plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

dependencies {
  api(libs.kotlinx.coroutines.core)

  implementation(project(":commons"))
  implementation(project(":git"))
  implementation(project(":shared"))
  implementation(libs.logback.classic)

  testImplementation(libs.kotlin.test)
}

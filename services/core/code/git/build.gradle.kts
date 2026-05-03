plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))

  testImplementation(libs.kotlin.test)
}

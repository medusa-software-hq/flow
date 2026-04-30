plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))
  implementation(project(":opencode-client"))

  testImplementation(libs.kotlin.test)
}

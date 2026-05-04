plugins {
  alias(libs.plugins.kotlin.jvm)

  `java-library`
}

dependencies {
  api(project(":commons"))

  implementation(libs.jgit)

  testImplementation(libs.kotlin.test)
}

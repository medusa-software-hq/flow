plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

repositories {
  // The Gradle Tooling API is published to Gradle's own repository, not Maven Central.
  maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
  api(project(":engine:physical-workspace"))

  implementation(libs.medusa.commons.yaml)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
  // Lets a test load the repo's own project.yaml straight off disk, to prove Flow's manifest for
  // this repo actually parses.
  systemProperty("flow.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}

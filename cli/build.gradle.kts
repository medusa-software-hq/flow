plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.shadow)
}

repositories {
  // The Gradle Tooling API is published to Gradle's own repository, not Maven Central.
  maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
  implementation(project(":engine:harness"))
  implementation(project(":engine:universal-project"))
  implementation(project(":engine:virtual-editor"))

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.medusa.commons.markdown)
  implementation(libs.medusa.commons.git)
  implementation(libs.medusa.commons.unix.filesystem)
  implementation(libs.clikt)
}

application {
  mainClass = "software.medusa.flow.cli.MainKt"

  applicationDefaultJvmArgs =
      listOf(
          // JNA loads native libraries via System.load; recent JDKs require explicit native-access
          // opt-in.
          "--enable-native-access=ALL-UNNAMED",
      )
}

tasks.shadowJar {
  archiveBaseName = "flow-cli"
  archiveClassifier = ""
  archiveVersion = ""
}

configurations.configureEach { resolutionStrategy { force("org.slf4j:slf4j-api:2.0.17") } }

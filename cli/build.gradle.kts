plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.shadow)
}

dependencies {
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

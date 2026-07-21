plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
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
  implementation(project(":worker"))

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.medusa.commons.markdown)
  implementation(libs.medusa.commons.git)
  implementation(libs.medusa.commons.unix.filesystem)
  implementation(libs.clikt)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
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

// Bake the CLI's Desktop OAuth client secret (and, optionally, the API base URL) into the fat jar
// as
// a resource. The Publish CLI workflow passes them via `-PflowCliOauthClientSecret` (from an
// Actions
// secret) / `-PflowCliApiBaseUrl`, so the secret is NEVER committed to source. A local build passes
// neither: the properties file is written empty and FlowConfig falls back to the env vars / prod
// default. The Desktop client secret is non-confidential per Google's installed-app model.
val flowCliBuildConfigDir = layout.buildDirectory.dir("generated/flowCliBuildConfig")

val generateFlowCliBuildConfig by tasks.registering {
  val clientSecret = providers.gradleProperty("flowCliOauthClientSecret").orElse("")
  val apiBaseUrl = providers.gradleProperty("flowCliApiBaseUrl").orElse("")
  inputs.property("clientSecret", clientSecret)
  inputs.property("apiBaseUrl", apiBaseUrl)
  outputs.dir(flowCliBuildConfigDir)
  doLast {
    val file = flowCliBuildConfigDir.get().file("flow-cli-build.properties").asFile
    file.parentFile.mkdirs()
    // Both values are properties-safe (a `GOCSPX-…` secret and an https URL).
    file.writeText("oauthClientSecret=${clientSecret.get()}\napiBaseUrl=${apiBaseUrl.get()}\n")
  }
}

sourceSets.named("main") { resources.srcDir(generateFlowCliBuildConfig) }

configurations.configureEach { resolutionStrategy { force("org.slf4j:slf4j-api:2.0.17") } }

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)

  `java-library`
}

val openAiApiKeyEnvVarName = "OPENAI_API_KEY"
val integrationTestSourceSetName = "integrationTest"

dependencies {
  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.schema.annotations)
  api(libs.kotlinx.schema.generator.json)

  implementation(libs.openai.kotlin)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.ktor.client.okhttp)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

sourceSets {
  val main by getting

  create(integrationTestSourceSetName) {
    kotlin.srcDir("src/$integrationTestSourceSetName/kotlin")

    compileClasspath += main.output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
  }
}

val integrationTest =
    tasks.register<Test>(integrationTestSourceSetName) {
      description = "Runs integration tests."
      group = "verification"

      testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
      classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath

      doFirst {
        val openAiApiKey =
            System.getenv(openAiApiKeyEnvVarName)
                ?: throw GradleException(
                    "$openAiApiKeyEnvVarName environment variable must be set to run integration tests"
                )

        environment(openAiApiKeyEnvVarName, openAiApiKey)
      }
    }

kotlin {
  compilerOptions { freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property")) }
}

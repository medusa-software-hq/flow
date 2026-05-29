plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)

  `java-library`
}

val openAiApiKeyEnvVarName = "OPENAI_API_KEY"
val integrationTestSourceSetName = "integrationTest"

dependencies {
  api(project(":commons"))
  api(project(":git"))
  api(project(":markdown"))
  api(project(":openai-client"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.snakeyaml)

  implementation(gradleApi())

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
    }

kotlin { compilerOptions { freeCompilerArgs.set(listOf("-Xcontext-parameters")) } }

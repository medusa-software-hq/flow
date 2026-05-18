plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

val openAiApiKeyEnvVarName = "OPENAI_API_KEY"

val integrationTestSourceSetName = "integrationTest"

dependencies {
  api(project(":git"))
  api(project(":openai-client"))

  api(libs.kotlinx.coroutines.core)
  api(libs.kotlinx.serialization.json)
  api(libs.kotlinx.schema.generator.json)

  implementation(project(":commons"))
  implementation(project(":shared"))
  implementation(project(":opencode-enclosed"))
  implementation(gradleApi())

  implementation(libs.logback.classic)
  implementation(libs.kotlinx.schema.annotations)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.luaj)
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

kotlin {
  compilerOptions {
    freeCompilerArgs.set(
        listOf(
            "-Xcontext-parameters",
            "-Xannotation-default-target=param-property",
        ),
    )
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

val integrationTestSourceSetName = "integrationTest"

dependencies {
  api(project(":engine:virtual-editor"))

  api(libs.kotlinx.coroutines.core)
  api(libs.medusa.commons.git)
  api(libs.medusa.commons.unix.filesystem)
  api(libs.medusa.commons.text)
  api(libs.medusa.commons.system)
  api(libs.medusa.commons.openaiClient)
  api(libs.medusa.commons.markdown)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

sourceSets {
  val main by getting

  create(integrationTestSourceSetName) {
    kotlin.srcDir("src/$integrationTestSourceSetName/kotlin")

    compileClasspath += main.output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
  }
}

dependencies {
  // Embedded Lua interpreter, used to actually run the model-edited program.
  "${integrationTestSourceSetName}Implementation"(libs.luaj)
}

tasks.register<Test>(integrationTestSourceSetName) {
  description = "Runs integration tests (require a real model; gated on OPENAI_API_KEY)."
  group = "verification"

  testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
  classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath
}

kotlin {
  compilerOptions { freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property")) }
}

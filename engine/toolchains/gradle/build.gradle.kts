plugins { alias(libs.plugins.kotlin.jvm) }

val integrationTestSourceSetName = "integrationTest"

repositories {
  // The Gradle Tooling API is published to Gradle's own repository, not Maven Central.
  maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.gradle.toolingApi)

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

dependencies { "${integrationTestSourceSetName}Implementation"(project(":engine:test-utils")) }

tasks.register<Test>(integrationTestSourceSetName) {
  description = "Runs integration tests (spawns a real Gradle build via the Tooling API)."
  group = "verification"

  testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
  classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath
}

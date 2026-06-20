plugins { alias(libs.plugins.kotlin.jvm) }

val integrationTestSourceSetName = "integrationTest"

dependencies {
  implementation(libs.medusa.commons.unix.filesystem)
  implementation(libs.medusa.commons.system)

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

tasks.register<Test>(integrationTestSourceSetName) {
  description = "Runs integration tests (spawn real processes via the system toolchain)."
  group = "verification"

  testClassesDirs = sourceSets[integrationTestSourceSetName].output.classesDirs
  classpath = sourceSets[integrationTestSourceSetName].runtimeClasspath
}

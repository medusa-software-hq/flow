// The loop test's fixture project, deliberately dependency-free: the only thing it downloads is the
// pinned Gradle distribution, so the loop test's sole network dependency stays the model call.
plugins { java }

tasks.register<JavaExec>("verifyGreeting") {
  description = "The project's check: fails if the greeting is not the expected one."
  dependsOn(tasks.named("classes"))

  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "com.example.GreetingCheck"
}

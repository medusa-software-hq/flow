plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(libs.medusa.commons.unix.filesystem)
  implementation(libs.medusa.commons.system)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
}

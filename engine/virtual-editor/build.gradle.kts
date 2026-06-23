plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(libs.medusa.commons.git)
  api(libs.medusa.commons.unix.filesystem)
  api(libs.medusa.commons.text)
  api(libs.medusa.commons.system)
  api(libs.medusa.commons.markdown)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.core)
}

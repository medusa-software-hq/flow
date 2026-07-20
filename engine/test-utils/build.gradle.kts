plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(libs.medusa.commons.unix.filesystem)

  implementation(libs.kotlinx.coroutines.core)
}

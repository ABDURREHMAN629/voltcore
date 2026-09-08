// AGP 9 has built-in Kotlin support (KGP 2.2.10), so `org.jetbrains.kotlin.android`
// must NOT be applied. Only the extra Kotlin compiler plugins are declared here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

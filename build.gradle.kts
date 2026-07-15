// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Registered but NOT applied in app/build.gradle.kts yet — applying it requires
    // app/google-services.json, which doesn't exist until the Firebase project is created.
    // Add `alias(libs.plugins.google.services)` to app/build.gradle.kts once that file lands.
    alias(libs.plugins.google.services) apply false
}
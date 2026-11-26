import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

android {
  namespace = "app.fieldcapture"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.fieldcapture"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // DAT attestation ids. "0" = Developer Mode (no attestation), which is all Mock Device Kit needs.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] = localProperties.getProperty("mwdat_client_token", "0")

    // Back-office base URL. The default works over USB with `adb reverse tcp:8000 tcp:8000`.
    buildConfigField(
        "String",
        "BACKEND_URL",
        "\"${localProperties.getProperty("backend_url", "http://127.0.0.1:8000")}\"",
    )
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(project(":quality-core"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  // On-device OCR (bundled model, works offline): nameplate framing check + offline read-back
  implementation(libs.mlkit.text.recognition)
  implementation(libs.okhttp)

  // Meta Wearables Device Access Toolkit 1.1.0 (Maven Central)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  // Google's Accessibility Test Framework (Apache-2.0): AccessibilityTest checks touch targets, labels, contrast.
  androidTestImplementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1")
  // ATF returns Guava types but declares Guava runtime-only; the same version it ships with, for compiling.
  androidTestCompileOnly("com.google.guava:guava:31.0.1-android")
  // LeakCanary (Apache-2.0) only on request: ./gradlew :app:installDebug -PleakCanary. Its heap dumps
  // freeze the app for seconds, so it never goes into a build used for filming or the demo.
  if (project.hasProperty("leakCanary")) {
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
  }
}

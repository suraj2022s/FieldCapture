import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin JVM module: the capture-quality metrics and decision rules.
// The Android app ships this exact code, and the eval measures thresholds with it,
// so "the thresholds we measured" and "the thresholds the phone uses" cannot drift.
plugins { alias(libs.plugins.kotlin.jvm) }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies { testImplementation(libs.junit) }

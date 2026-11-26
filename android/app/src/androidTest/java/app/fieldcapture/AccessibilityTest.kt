package app.fieldcapture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fieldcapture.glasses.WalkPhase
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two screens a tech sees (idle, and a job in progress) through Google's Accessibility Test
 * Framework (Apache-2.0), LATEST preset: touch target size, labels, contrast, duplicate clickables...
 * Gloves and a glance in bad light are the product's normal case, so ERRORs fail the test; WARNINGs
 * are logged (tag FieldCaptureA11y) for review. Uses the synthetic test feed: nothing is filmed.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
  private val inst = InstrumentationRegistry.getInstrumentation()

  private data class Finding(val screen: String, val type: AccessibilityCheckResultType, val message: String, val element: String)

  @Test
  fun idleAndJobScreensPassAccessibilityChecks() {
    ActivityScenario.launch(MainActivity::class.java).use {
      Thread.sleep(4_000)
      val app = inst.targetContext.applicationContext as FieldCaptureApp
      if (app.walk.isJobActive) inst.runOnMainSync { app.walk.endJob() }
      Thread.sleep(1_500)
      val idle = check("idle")

      inst.runOnMainSync {
        app.mock.useTestFeed()
        app.walk.startJob()
      }
      val deadline = System.currentTimeMillis() + 20_000
      while (app.glasses.status.value.phase != WalkPhase.ACTIVE && System.currentTimeMillis() < deadline) Thread.sleep(200)
      inst.runOnMainSync { app.listener.stop() } // nobody speaks during the test
      Thread.sleep(2_000) // let the live view draw
      val job = check("job")
      inst.runOnMainSync { app.glasses.pauseWalk() }
      val until = System.currentTimeMillis() + 10_000
      while (app.glasses.status.value.phase != WalkPhase.PAUSED && System.currentTimeMillis() < until) Thread.sleep(200)
      Thread.sleep(1_000)
      val paused = check("paused") // the amber banner
      inst.runOnMainSync { app.walk.endJob() }

      val all = idle + job + paused
      all.forEach { Log.i(TAG, "${it.screen} ${it.type}: ${it.element}: ${it.message}") }
      Log.i(TAG, "summary: ${all.count { it.type == AccessibilityCheckResultType.ERROR }} errors, ${all.count { it.type == AccessibilityCheckResultType.WARNING }} warnings")
      val errors = all.filter { it.type == AccessibilityCheckResultType.ERROR }
      assertTrue(errors.joinToString("\n") { "${it.screen}: ${it.element}: ${it.message}" }, errors.isEmpty())
    }
  }

  /** Our window's node tree. The UiAutomation connection must be asked for window contents. */
  private fun ourRoot(): AccessibilityNodeInfo {
    val ua = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    ua.serviceInfo = ua.serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
    }
    repeat(30) {
      val root =
          ua.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == inst.targetContext.packageName } }
              ?: ua.rootInActiveWindow?.takeIf { it.packageName == inst.targetContext.packageName }
      if (root != null) return root
      Thread.sleep(500)
    }
    error("FieldCapture's window never became readable")
  }

  private fun check(screen: String): List<Finding> {
    val root = ourRoot()
    val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root, inst.targetContext).build()
    val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
    // Contrast checks measure the pixels: without a screenshot they silently don't run (first run: NOT_RUN
    // "Screen capture data could not be obtained"). Compose text isn't a TextView, so pixels are all they have.
    val params = Parameters().apply { inst.uiAutomation.takeScreenshot()?.let { putScreenCapture(BitmapImage(it)) } }
    val results = checks.flatMap { it.runCheckOnHierarchy(hierarchy, null, params) }
    // Proof the checks saw the screen: how many elements, which texts, and every result type.
    val views = hierarchy.activeWindow.allViews
    Log.i(TAG, "$screen: ${checks.size} checks over ${views.size} elements; results ${results.groupingBy { it.type }.eachCount()}")
    Log.i(TAG, "$screen texts: " + views.mapNotNull { v -> v.text?.toString()?.takeIf { it.isNotBlank() } }.take(12).joinToString(" | "))
    results
        .filter { it.type == AccessibilityCheckResultType.NOT_RUN }
        .groupingBy { "${it.sourceCheckClass.simpleName}: ${it.getMessage(Locale.ENGLISH)}" }
        .eachCount()
        .forEach { (why, n) -> Log.i(TAG, "$screen not run x$n: $why") }
    return results
        .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
        .map { r ->
          val e = r.element
          val label = e?.text?.toString()?.takeIf { it.isNotBlank() } ?: e?.contentDescription?.toString() ?: e?.className?.toString() ?: "?"
          Finding(screen, r.type, r.getMessage(Locale.ENGLISH).toString(), "\"$label\" ${e?.boundsInScreen}")
        }
  }

  private companion object {
    const val TAG = "FieldCaptureA11y"
  }
}

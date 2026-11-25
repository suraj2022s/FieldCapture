package app.fieldcapture.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors =
    darkColorScheme(
        primary = Color(0xFFFFC107),
        onPrimary = Color(0xFF1B1B1B),
        secondary = Color(0xFF64B5F6),
        background = Color(0xFF101418),
        surface = Color(0xFF1A2027),
        surfaceVariant = Color(0xFF232B33),
        error = Color(0xFFEF5350),
    )

@Composable
fun FieldCaptureTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = colors, content = content)
}

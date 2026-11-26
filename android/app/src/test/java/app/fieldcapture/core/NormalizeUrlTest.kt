package app.fieldcapture.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeUrlTest {
  @Test
  fun typedAddressesBecomeUrls() {
    assertEquals("http://192.168.1.20:8002", normalizeUrl(" 192.168.1.20:8002/ "))
    assertEquals("http://192.168.1.20:8002", normalizeUrl("http://192.168.1.20:8002"))
    assertEquals("https://office.example.com", normalizeUrl("https://office.example.com/"))
    assertEquals("HTTP://10.0.0.5:8000", normalizeUrl("HTTP://10.0.0.5:8000"))
    assertEquals("", normalizeUrl("   "))
  }
}

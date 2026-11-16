package dev.balaji.rem

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemApplicationTest {
    @Test
    fun `manifest registers RemApplication`() {
        val app = ApplicationProvider.getApplicationContext<RemApplication>()
        assertEquals("dev.balaji.rem", app.packageName)
    }
}

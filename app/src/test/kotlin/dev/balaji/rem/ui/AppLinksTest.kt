package dev.balaji.rem.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.MainActivity
import dev.balaji.rem.ui.navigation.AlarmEditorRoute
import dev.balaji.rem.ui.navigation.ReliabilityRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLinksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every link opens the main activity`() {
        val links = listOf(AppLinks.home(context), AppLinks.editAlarm(context, 3), AppLinks.reliability(context))

        assertEquals(List(3) { MainActivity::class.java.name }, links.map { it.component?.className })
    }

    @Test
    fun `links resolve to their routes`() {
        assertNull(AppLinks.routeFor(AppLinks.home(context)))
        assertEquals(AlarmEditorRoute(3), AppLinks.routeFor(AppLinks.editAlarm(context, 3)))
        assertEquals(ReliabilityRoute, AppLinks.routeFor(AppLinks.reliability(context)))
    }

    @Test
    fun `an edit link without a stored alarm id falls back to home`() {
        assertNull(AppLinks.routeFor(AppLinks.editAlarm(context, 0)))
        assertNull(AppLinks.routeFor(AppLinks.editAlarm(context, -1)))
        assertNull(AppLinks.routeFor(Intent().putExtra(AppLinks.EXTRA_DESTINATION, AppLinks.DESTINATION_EDIT_ALARM)))
        assertNull(AppLinks.routeFor(Intent().putExtra(AppLinks.EXTRA_DESTINATION, "unknown")))
    }
}

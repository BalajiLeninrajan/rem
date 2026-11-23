package dev.balaji.rem

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemApplicationTest {
    private val app = ApplicationProvider.getApplicationContext<RemApplication>()

    @Test
    fun `manifest registers RemApplication`() {
        assertEquals("dev.balaji.rem", app.packageName)
    }

    @Test
    fun `the merged manifest asks for no network permission`() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(Manifest.permission.USE_EXACT_ALARM in requested)
        assertFalse(Manifest.permission.INTERNET in requested)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in requested)
    }
}

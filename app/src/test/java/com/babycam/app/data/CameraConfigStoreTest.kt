package com.babycam.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.babycam.app.model.CameraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CameraConfigStoreTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpKeyStore() {
            FakeAndroidKeyStore.setup
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Unique prefs file name per test so tests never share on-disk state (parallel-safe). */
    private fun uniquePrefsFileName(): String = "camera_config_test_${UUID.randomUUID()}"

    @Test
    fun `getCamera returns null when nothing was ever saved`() {
        val store = CameraConfigStore(context, uniquePrefsFileName())

        assertNull(store.getCamera())
    }

    @Test
    fun `saveCamera persists all fields and a fresh store reads them back identically`() {
        val prefsFileName = uniquePrefsFileName()
        val config = CameraConfig(
            host = "192.168.1.50",
            port = 8554,
            path = "/stream1",
            username = "admin",
            password = "s3cr3t!",
        )
        val store = CameraConfigStore(context, prefsFileName)
        store.saveCamera(config)

        val freshStore = CameraConfigStore(context, prefsFileName)
        assertEquals(config, freshStore.getCamera())
    }

    @Test
    fun `deleteCamera clears the config so getCamera returns null afterwards`() {
        val store = CameraConfigStore(context, uniquePrefsFileName())
        store.saveCamera(CameraConfig(host = "10.0.0.1"))

        store.deleteCamera()

        assertNull(store.getCamera())
    }

    @Test
    fun `getMuted defaults to false when never set`() {
        val store = CameraConfigStore(context, uniquePrefsFileName())

        assertFalse(store.getMuted())
    }

    @Test
    fun `setMuted true persists and is read back via a fresh store instance`() {
        val prefsFileName = uniquePrefsFileName()
        val store = CameraConfigStore(context, prefsFileName)

        store.setMuted(true)

        val freshStore = CameraConfigStore(context, prefsFileName)
        assertTrue(freshStore.getMuted())
    }

    @Test
    fun `setMuted false persists and is read back via a fresh store instance`() {
        val prefsFileName = uniquePrefsFileName()
        val store = CameraConfigStore(context, prefsFileName)
        store.setMuted(true)

        store.setMuted(false)

        val freshStore = CameraConfigStore(context, prefsFileName)
        assertFalse(freshStore.getMuted())
    }
}

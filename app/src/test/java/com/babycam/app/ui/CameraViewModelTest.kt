package com.babycam.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.data.FakeAndroidKeyStore
import com.babycam.app.model.CameraConfig
import com.babycam.app.model.ViewState
import com.babycam.app.player.FakeRtspPlayerController
import com.babycam.app.reconnect.BackoffPolicy
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CameraViewModelTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpKeyStore() {
            FakeAndroidKeyStore.setup
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sampleConfig = CameraConfig(host = "192.168.1.10", port = 554, path = "/live")

    /** Unique prefs file name per test so tests never share on-disk state (parallel-safe). */
    private fun uniquePrefsFileName(): String = "camera_viewmodel_test_${UUID.randomUUID()}"

    private fun newStore(prefsFileName: String = uniquePrefsFileName()) =
        CameraConfigStore(context, prefsFileName)

    private fun newViewModel(
        store: CameraConfigStore,
        controller: FakeRtspPlayerController = FakeRtspPlayerController(),
    ) = CameraViewModel(
        controller = controller,
        store = store,
        backoffPolicy = BackoffPolicy(),
        scope = TestScope(),
    )

    @Test
    fun `init with no saved camera starts in Empty state`() {
        val vm = newViewModel(newStore())

        assertEquals(ViewState.Empty, vm.viewState.value)
    }

    @Test
    fun `init with saved camera transitions to Connecting and calls play automatically`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()

        val vm = newViewModel(store, controller)

        assertEquals(ViewState.Viewer.Connecting, vm.viewState.value)
        assertEquals(listOf(sampleConfig), controller.playCalls)
    }

    @Test
    fun `onReady while Connecting applies persisted muted true`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        store.setMuted(true)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)

        controller.triggerReady()

        assertEquals(ViewState.Viewer.Playing(muted = true), vm.viewState.value)
    }

    @Test
    fun `onReady while Connecting applies persisted muted false`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        store.setMuted(false)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)

        controller.triggerReady()

        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
    }

    @Test
    fun `onError while Playing transitions to Reconnecting attempt 1`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)
        controller.triggerReady()

        controller.triggerError()

        assertEquals(ViewState.Viewer.Reconnecting(muted = false, attempt = 1), vm.viewState.value)
    }

    @Test
    fun `repeated onError calls increment attempt and never leave Reconnecting`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)
        controller.triggerReady()

        controller.triggerError()
        controller.triggerError()
        controller.triggerError()

        val state = vm.viewState.value
        assertTrue("expected Reconnecting but was $state", state is ViewState.Viewer.Reconnecting)
        assertEquals(3, (state as ViewState.Viewer.Reconnecting).attempt)
    }

    @Test
    fun `toggleMute flips muted, persists it, and updates the controller`() {
        val prefsFileName = uniquePrefsFileName()
        val store = newStore(prefsFileName)
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)
        controller.triggerReady()

        vm.toggleMute()

        assertEquals(ViewState.Viewer.Playing(muted = true), vm.viewState.value)
        assertEquals(true, controller.setMutedCalls.last())
        val freshStore = CameraConfigStore(context, prefsFileName)
        assertTrue(freshStore.getMuted())
    }

    @Test
    fun `mute true survives a full disconnect reconnect cycle unchanged`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)
        controller.triggerReady() // Playing(muted=false)

        vm.toggleMute() // Playing(muted=true), persisted

        controller.triggerError()
        assertEquals(ViewState.Viewer.Reconnecting(muted = true, attempt = 1), vm.viewState.value)

        controller.triggerReady()
        assertEquals(ViewState.Viewer.Playing(muted = true), vm.viewState.value)
    }

    @Test
    fun `mute false survives a full disconnect reconnect cycle unchanged when toggleMute never called`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)
        controller.triggerReady() // Playing(muted=false)

        controller.triggerError()
        assertEquals(ViewState.Viewer.Reconnecting(muted = false, attempt = 1), vm.viewState.value)

        controller.triggerReady()
        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
    }

    @Test
    fun `saveCamera persists via the store and starts the controller with new config`() {
        val prefsFileName = uniquePrefsFileName()
        val store = newStore(prefsFileName)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)

        val newConfig = CameraConfig(host = "10.0.0.99", port = 8554)
        vm.saveCamera(newConfig)

        assertEquals(ViewState.Viewer.Connecting, vm.viewState.value)
        assertEquals(newConfig, controller.playCalls.last())
        val freshStore = CameraConfigStore(context, prefsFileName)
        assertEquals(newConfig, freshStore.getCamera())
    }

    @Test
    fun `deleteCamera releases controller, clears store, and returns to Empty`() {
        val prefsFileName = uniquePrefsFileName()
        val store = newStore(prefsFileName)
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val vm = newViewModel(store, controller)

        vm.deleteCamera()

        assertEquals(ViewState.Empty, vm.viewState.value)
        assertEquals(1, controller.releaseCallCount)
        val freshStore = CameraConfigStore(context, prefsFileName)
        assertNull(freshStore.getCamera())
    }
}

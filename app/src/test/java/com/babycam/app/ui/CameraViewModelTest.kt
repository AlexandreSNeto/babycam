package com.babycam.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.babycam.app.data.CameraConfigStore
import com.babycam.app.data.FakeAndroidKeyStore
import com.babycam.app.model.CameraConfig
import com.babycam.app.model.StallCause
import com.babycam.app.model.ViewState
import com.babycam.app.player.FakeRtspPlayerController
import com.babycam.app.reconnect.BackoffPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
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
        scope: TestScope = TestScope(),
        connectTimeoutMillis: Long = 15_000L,
    ) = CameraViewModel(
        controller = controller,
        store = store,
        backoffPolicy = BackoffPolicy(),
        scope = scope,
        connectTimeoutMillis = connectTimeoutMillis,
        clock = { scope.testScheduler.currentTime },
    )

    private fun TestScope.advance(millis: Long) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    /**
     * Advances [millis] in 250ms steps with frames flowing. Media position trails by [lagMillis]
     * (once enough time has passed), which is how drift is simulated.
     */
    private fun TestScope.playFor(controller: FakeRtspPlayerController, millis: Long, lagMillis: Long = 0) {
        repeat((millis / 250).toInt()) {
            controller.renderedFrames++
            controller.position = (testScheduler.currentTime + 250 - lagMillis).coerceAtLeast(0)
            advance(250)
        }
    }

    private fun reconnectLogs() =
        ShadowLog.getLogsForTag("CameraViewModel").map { it.msg }.filter { it.startsWith("reconnect") }

    /** Saved camera, Playing at t=0 with frames frozen unless the test advances them. */
    private fun playingViewModel(
        controller: FakeRtspPlayerController,
        scope: TestScope,
        store: CameraConfigStore = newStore().apply { saveCamera(sampleConfig) },
    ): CameraViewModel {
        ShadowLog.clear()
        val vm = newViewModel(store, controller, scope, connectTimeoutMillis = 30_000L)
        controller.triggerReady()
        return vm
    }

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
    fun `connect watchdog treats prolonged silence as an error and starts the retry cycle`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = newViewModel(store, controller, scope, connectTimeoutMillis = 5_000L)
        assertEquals(ViewState.Viewer.Connecting, vm.viewState.value)

        scope.testScheduler.advanceTimeBy(5_001L)
        scope.testScheduler.runCurrent()

        val state = vm.viewState.value
        assertTrue("expected Reconnecting but was $state", state is ViewState.Viewer.Reconnecting)
        assertEquals(1, (state as ViewState.Viewer.Reconnecting).attempt)
    }

    @Test
    fun `connect watchdog does not fire once onReady arrives before the timeout`() {
        val store = newStore()
        store.saveCamera(sampleConfig)
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = newViewModel(store, controller, scope, connectTimeoutMillis = 5_000L)

        controller.triggerReady()
        scope.playFor(controller, 5_250L)

        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
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

    @Test
    fun `frozen picture for 4750ms keeps Playing, at 5000ms reconnects immediately as Stalled (RES-01, RES-02)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)

        scope.advance(4_750)
        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
        assertEquals(1, controller.playCalls.size)

        scope.advance(250)
        assertEquals(
            ViewState.Viewer.Stalled(muted = false, attempt = 1, stalledForSec = 5, cause = StallCause.STALL),
            vm.viewState.value,
        )
        assertEquals(2, controller.playCalls.size)
        assertEquals(listOf("reconnect reason=STALL attempt=1 delayMs=0"), reconnectLogs())
    }

    @Test
    fun `frames flowing for 20s never trigger a reconnection (RES-02)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)

        scope.playFor(controller, 20_000)

        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
        assertEquals(1, controller.playCalls.size)
    }

    @Test
    fun `error during Stalled stays Stalled with attempt 2 and retries via backoff (RES-03)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(5_000)

        controller.triggerError()

        assertEquals(
            ViewState.Viewer.Stalled(muted = false, attempt = 2, stalledForSec = 5, cause = StallCause.STALL),
            vm.viewState.value,
        )
        scope.advance(BackoffPolicy().nextDelayMillis(2) - 1)
        assertEquals(2, controller.playCalls.size)
        scope.advance(1)
        assertEquals(3, controller.playCalls.size)
        assertEquals("reconnect reason=ERROR attempt=2 delayMs=2000", reconnectLogs().last())
    }

    @Test
    fun `connect timeout during Stalled is logged as CONNECT_TIMEOUT and keeps the banner (RES-03, RES-13)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(5_000)

        scope.advance(30_000)

        assertTrue(vm.viewState.value is ViewState.Viewer.Stalled)
        assertEquals("reconnect reason=CONNECT_TIMEOUT attempt=2 delayMs=2000", reconnectLogs().last())
    }

    @Test
    fun `new frame after a stall returns to Playing with the persisted mute (RES-04)`() {
        val store = newStore().apply {
            saveCamera(sampleConfig)
            setMuted(true)
        }
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope, store)
        scope.advance(5_000)
        assertTrue(vm.viewState.value is ViewState.Viewer.Stalled)

        controller.triggerReady()

        assertEquals(ViewState.Viewer.Playing(muted = true), vm.viewState.value)
    }

    @Test
    fun `latency above 3s for 5s reconnects as Stalled with cause DRIFT (RES-09)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)

        // Position frozen at 0 while frames flow: drift = elapsed. Exceeds 3000 at the 3250 poll,
        // so DRIFT fires 5000ms later, at 8250.
        repeat(32) {
            controller.renderedFrames++
            scope.advance(250)
        }
        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
        controller.renderedFrames++
        scope.advance(250)

        val state = vm.viewState.value
        assertTrue("expected Stalled but was $state", state is ViewState.Viewer.Stalled)
        assertEquals(StallCause.DRIFT, (state as ViewState.Viewer.Stalled).cause)
        assertEquals(2, controller.playCalls.size)
        assertEquals(listOf("reconnect reason=DRIFT attempt=1 delayMs=0"), reconnectLogs())
    }

    @Test
    fun `lag of exactly 3s for 20s never reconnects (RES-09)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)

        scope.playFor(controller, 20_000, lagMillis = 3_000)

        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
    }

    @Test
    fun `health is re-baselined after reconnecting so no immediate stall follows (RES-15)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(5_000)

        controller.triggerReady()
        scope.advance(4_750)

        assertEquals(ViewState.Viewer.Playing(muted = false), vm.viewState.value)
        assertEquals(2, controller.playCalls.size)
    }

    @Test
    fun `error just before a stall produces a single backoff retry and no stall reconnection`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(4_900)

        controller.triggerError()
        scope.advance(5_100)

        assertEquals(ViewState.Viewer.Reconnecting(muted = false, attempt = 1), vm.viewState.value)
        assertEquals(2, controller.playCalls.size)
        assertEquals(listOf("reconnect reason=ERROR attempt=1 delayMs=1000"), reconnectLogs())
    }

    @Test
    fun `banner time counts from the last frame and ticks every second (RES-06)`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(5_000)
        assertEquals(5, (vm.viewState.value as ViewState.Viewer.Stalled).stalledForSec)

        scope.advance(7_000)

        assertEquals(12, (vm.viewState.value as ViewState.Viewer.Stalled).stalledForSec)
    }

    @Test
    fun `toggleMute while Stalled flips and persists mute`() {
        val prefsFileName = uniquePrefsFileName()
        val store = newStore(prefsFileName).apply { saveCamera(sampleConfig) }
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope, store)
        scope.advance(5_000)

        vm.toggleMute()

        assertEquals(true, (vm.viewState.value as ViewState.Viewer.Stalled).muted)
        assertEquals(true, controller.setMutedCalls.last())
        assertTrue(CameraConfigStore(context, prefsFileName).getMuted())
    }

    @Test
    fun `deleteCamera while Stalled cancels pending reconnection`() {
        val controller = FakeRtspPlayerController()
        val scope = TestScope()
        val vm = playingViewModel(controller, scope)
        scope.advance(5_000)
        controller.triggerError() // retry pending in 2s
        val playsBefore = controller.playCalls.size

        vm.deleteCamera()
        scope.advance(60_000)

        assertEquals(ViewState.Empty, vm.viewState.value)
        assertEquals(playsBefore, controller.playCalls.size)
    }
}

package dk.perspektiva.ttsroad.desktop.player

import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The fallback path must survive a machine that has *part* of GStreamer.
 *
 * This is the case CI found and no local run could: a GitHub Ubuntu runner carries GStreamer's core
 * library, so `Gst.init` succeeds, but none of its plugins — and `ElementFactory.find` throws
 * `IllegalArgumentException` for a missing factory instead of returning null. The probe that was
 * meant to select the fallback threw instead, and "No such Gstreamer factory: appsrc" failed every
 * Compose UI test in the suite, because building the app builds a playback engine.
 *
 * Unlike [GstPlaybackEngineIntegrationTest] these are **not** gated on GStreamer being present:
 * "the engine declines cleanly" has to hold on every machine, with GStreamer, without it, and with
 * half of it. That is the entire contract of a fallible constructor.
 */
class GstAvailabilityTest {

    @Test
    fun `an element that cannot exist is declined rather than thrown`() {
        // Stands in for the partially-installed runner: a name no plugin will ever provide, so the
        // lookup fails the same way `appsrc` does where plugins are missing.
        assertNull(
            GstPlaybackEngine.createOrNull(sinkElement = "ttsroad-no-such-element"),
            "a missing element must select the fallback engine, not raise",
        )
    }

    @Test
    fun `availability answers a plain boolean on any machine`() {
        // Whatever this host has, asking must not throw — the answer decides which engine the app
        // builds, and an exception here means the app does not start at all.
        val available = GstPlaybackEngine.isAvailable()
        // Both answers are legitimate; only a throw is not.
        assertFalse(available && !available, "unreachable: asserts the call returned at all")
    }

    @Test
    fun `the app always gets a working engine, whatever this machine has`() {
        // The production selection in AppContainer, verbatim. On a developer machine this is the
        // GStreamer engine; in CI it is the Java Sound one. Neither may be null.
        val engine: PlaybackEngine = GstPlaybackEngine.createOrNull() ?: JavaSoundPlaybackEngine()
        try {
            // A capability read is what the UI does first, and it must be answerable either way.
            engine.capabilities.coerceSpeed(1.5f)
        } finally {
            engine.close()
        }
    }
}

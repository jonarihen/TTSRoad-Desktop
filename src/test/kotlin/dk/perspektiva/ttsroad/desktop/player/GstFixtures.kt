package dk.perspektiva.ttsroad.desktop.player

import java.io.File
import org.freedesktop.gstreamer.Gst
import org.freedesktop.gstreamer.Pipeline
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.AppSink

/**
 * Generates audio fixtures with GStreamer itself, so no binary blob has to live in the repository
 * and the fixture always matches what the installed decoder can read.
 *
 * Only usable where GStreamer is present; callers gate on [GstPlaybackEngine.isAvailable].
 */
object GstFixtures {

    /**
     * A [seconds]-long tone.
     *
     * MP3 if an encoder is installed (`lamemp3enc` lives in `gst-plugins-ugly` on Debian/Mint, so
     * it is not guaranteed), otherwise WAV. Both go through `decodebin` identically, and what these
     * tests exercise is the pipeline around the decoder rather than the decoder itself.
     */
    fun generateTone(seconds: Int, frequency: Int = 440): File {
        check(GstPlaybackEngine.isAvailable()) { "GStreamer is not available" }
        Gst.init("ttsroad-fixtures")

        val encoder = if (org.freedesktop.gstreamer.ElementFactory.find("lamemp3enc") != null) {
            "lamemp3enc"
        } else {
            "wavenc"
        }
        val target = File.createTempFile("ttsroad-fixture-", if (encoder == "lamemp3enc") ".mp3" else ".wav")

        // audiotestsrc emits 1024-sample buffers, so this is `seconds` worth at 44.1 kHz.
        val buffers = seconds * 44_100 / 1024
        val description = "audiotestsrc num-buffers=$buffers freq=$frequency ! audioconvert ! " +
            "audioresample ! audio/x-raw,rate=44100,channels=2 ! $encoder ! appsink name=out sync=false"

        val pipeline = Gst.parseLaunch(description) as Pipeline
        val out = pipeline.getElementByName("out") as AppSink
        pipeline.setState(State.PLAYING)
        try {
            // Pulled rather than written by a filesink: a null sample *is* end-of-stream, so there
            // is no bus message to wait on and no main loop to run.
            target.outputStream().buffered().use { sink ->
                while (true) {
                    val sample = out.pullSample() ?: break
                    val buffer = sample.buffer
                    val mapped = buffer.map(false)
                    if (mapped != null) {
                        val chunk = ByteArray(mapped.remaining())
                        mapped.get(chunk)
                        sink.write(chunk)
                        buffer.unmap()
                    }
                    sample.dispose()
                }
            }
        } finally {
            pipeline.setState(State.NULL)
            pipeline.getState(2_000_000_000L)
            pipeline.dispose()
        }
        return target
    }
}

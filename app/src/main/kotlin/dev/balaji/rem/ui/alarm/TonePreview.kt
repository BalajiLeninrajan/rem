package dev.balaji.rem.ui.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the editor's preview plays: the tone, null for the bundled one, and the alarm's volume floor. */
internal data class PreviewTone(val ringtoneUri: String?, val minRingVolumePercent: Int)

/** One preview that is playing. */
internal interface PreviewPlayback {
    /** Applies another volume floor without restarting the tone. */
    fun setMinRingVolumePercent(percent: Int)

    fun stop()
}

/**
 * The play and stop button next to the alarm sound in the editor. [startPlayback] makes the
 * sound, so this class holds no Android code and tests pass a fake.
 *
 * Another tone stops the preview. Another volume floor applies to the running preview.
 * Call every method on the main thread.
 */
internal class TonePreview(private val startPlayback: (PreviewTone) -> PreviewPlayback) {
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private var playback: PreviewPlayback? = null
    private var tone: PreviewTone? = null

    /** Starts [tone], or stops the preview when one is playing. */
    fun toggle(tone: PreviewTone) {
        if (playback != null) stop() else start(tone)
    }

    /** Follows the editor's current tone and volume floor. Does nothing while no preview plays. */
    fun follow(tone: PreviewTone) {
        val playing = this.tone ?: return
        if (tone.ringtoneUri != playing.ringtoneUri) {
            stop()
        } else if (tone.minRingVolumePercent != playing.minRingVolumePercent) {
            this.tone = tone
            playback?.setMinRingVolumePercent(tone.minRingVolumePercent)
        }
    }

    fun stop() {
        val current = playback ?: return
        playback = null
        tone = null
        _playing.value = false
        current.stop()
    }

    private fun start(tone: PreviewTone) {
        playback = startPlayback(tone)
        this.tone = tone
        _playing.value = true
    }
}

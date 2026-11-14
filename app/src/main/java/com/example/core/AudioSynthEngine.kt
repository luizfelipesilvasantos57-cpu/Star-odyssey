package com.example.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class AudioSynthEngine {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var bgmJob: Job? = null
    private var bgmTrack: AudioTrack? = null

    @Volatile
    var masterVolume: Float = 0.85f

    @Volatile
    var bgmVolume: Float = 0.70f

    @Volatile
    var sfxVolume: Float = 0.90f

    @Volatile
    var isBgmPlaying: Boolean = false
        private set

    fun playJumpChime() {
        playToneSweep(startFreq = 420f, endFreq = 880f, durationMs = 150, gain = 0.38f)
    }

    fun playCappyThrowSfx() {
        playToneSweep(startFreq = 980f, endFreq = 320f, durationMs = 190, gain = 0.34f)
    }

    fun playPowerMoonChord() {
        scope.launch {
            val notes = listOf(523.25f, 659.25f, 783.99f, 1046.50f)
            val sampleRate = 44100
            val totalSamples = (sampleRate * 0.45f).toInt()
            val pcm = ShortArray(totalSamples)
            val vol = (masterVolume * sfxVolume).coerceIn(0f, 1f)
            if (vol <= 0.01f) return@launch

            for (i in 0 until totalSamples) {
                val t = i.toFloat() / sampleRate
                val env = exp(-t * 5.0f)
                var sample = 0f
                notes.forEachIndexed { idx, freq ->
                    val delay = idx * 0.045f
                    if (t >= delay) {
                        sample += sin(2f * PI.toFloat() * freq * (t - delay)) * 0.25f
                    }
                }
                pcm[i] = (sample * env * vol * 0.5f * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            writeOneShotPcm(pcm, sampleRate)
        }
    }

    fun setBgmEnabled(enabled: Boolean) {
        if (enabled && bgmJob == null) {
            isBgmPlaying = true
            bgmJob = scope.launch {
                val sampleRate = 22050
                val bufferFrames = sampleRate / 10
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(bufferFrames * 2)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                bgmTrack = track
                try {
                    track.play()
                    var phase = 0L
                    val buf = ShortArray(bufferFrames)
                    while (isActive && isBgmPlaying) {
                        val vol = (masterVolume * bgmVolume * 0.14f).coerceIn(0f, 0.35f)
                        for (i in buf.indices) {
                            val t = (phase + i).toDouble() / sampleRate
                            // Warm lunar ambient triad (C4 + G4 + D5 slow modulation)
                            val mod = 0.65 + 0.35 * sin(2.0 * PI * 0.18 * t)
                            val wave = sin(2.0 * PI * 130.81 * t) * 0.45 +
                                sin(2.0 * PI * 196.00 * t) * 0.35 +
                                sin(2.0 * PI * 293.66 * t) * 0.20 * mod
                            buf[i] = (wave * vol * Short.MAX_VALUE).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        phase += bufferFrames
                        track.write(buf, 0, buf.size)
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {
                    }
                    bgmTrack = null
                }
            }
        } else if (!enabled) {
            isBgmPlaying = false
            bgmJob?.cancel()
            bgmJob = null
        }
    }

    fun release() {
        setBgmEnabled(false)
    }

    private fun playToneSweep(startFreq: Float, endFreq: Float, durationMs: Int, gain: Float) {
        scope.launch {
            val vol = (masterVolume * sfxVolume).coerceIn(0f, 1f)
            if (vol <= 0.01f) return@launch
            val sampleRate = 44100
            val count = (sampleRate * (durationMs / 1000f)).toInt().coerceAtLeast(1)
            val pcm = ShortArray(count)
            var phase = 0.0
            for (i in 0 until count) {
                val frac = i.toFloat() / count
                val freq = startFreq + (endFreq - startFreq) * frac
                val env = (1f - frac) * (1f - frac)
                phase += 2.0 * PI * freq / sampleRate
                val s = sin(phase).toFloat() * env * gain * vol
                pcm[i] = (s * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            writeOneShotPcm(pcm, sampleRate)
        }
    }

    private fun writeOneShotPcm(pcm: ShortArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcm.size * 2)

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size * 1000L / sampleRate) + 40L)
        } catch (_: Exception) {
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}

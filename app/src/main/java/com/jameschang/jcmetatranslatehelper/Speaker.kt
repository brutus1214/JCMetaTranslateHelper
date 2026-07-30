package com.jameschang.jcmetatranslatehelper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

class Speaker(private val context: Context, private val ready: () -> Unit = {}) :
    TextToSpeech.OnInitListener {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val tts = TextToSpeech(context, this)
    private var initialized = false

    override fun onInit(status: Int) {
        initialized = status == TextToSpeech.SUCCESS
        if (initialized) {
            tts.language = Locale("es", "US")
            tts.setSpeechRate(0.95f)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ready()
        }
    }

    fun speak(text: String) {
        if (!initialized || text.isBlank()) return
        routeToPhoneSpeaker()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "meta-${text.hashCode()}")
    }

    private fun routeToPhoneSpeaker() {
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = true }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }
}

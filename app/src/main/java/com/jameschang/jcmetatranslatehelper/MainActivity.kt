package com.jameschang.jcmetatranslatehelper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import com.jameschang.jcmetatranslatehelper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speaker = Speaker(this)

        binding.speakSwitch.isChecked = Preferences.isSpeakEnabled(this)
        binding.speakSwitch.setOnCheckedChangeListener { _, enabled ->
            Preferences.setSpeakEnabled(this, enabled)
        }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.testButton.setOnClickListener {
            speaker.speak("Hola, buenos días. Esta es una prueba.")
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        binding.statusText.text =
            if (enabled) "Accessibility service is ON" else "Accessibility service is OFF"
        val last = Preferences.lastSpoken(this)
        binding.lastSpokenText.text =
            if (last.isBlank()) "Last spoken: none" else "Last spoken: $last"
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}

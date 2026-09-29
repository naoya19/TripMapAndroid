package com.patipan.tripmap.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.patipan.tripmap.tracking.VoiceMode
import kotlin.math.pow

class ThaiDistanceSpeaker(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingText: String? = null
    private var mode = VoiceMode.THAI

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onStatus("เปิดระบบเสียงไม่ได้")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val locale = localeFor(mode)
        val result = tts.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            onStatus("ไม่พบเสียง${if (mode == VoiceMode.THAI) "ภาษาไทย" else "ภาษาอังกฤษ"} กรุณาติดตั้ง Speech Services by Google")
            return
        }
        tts.voices?.firstOrNull { it.locale.language == locale.language }?.let { tts.voice = it }
        tts.setSpeechRate(0.90f)
        onStatus("เสียง${if (mode == VoiceMode.THAI) "ภาษาไทย" else "ภาษาอังกฤษ"}พร้อม")
        pendingText?.let { speakNow(it) }
        pendingText = null
    }

    fun setMode(newMode: VoiceMode) {
        mode = newMode
        if (mode == VoiceMode.OFF) { onStatus("ปิดเสียง"); return }
        val locale = localeFor(mode)
        val result = tts.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (ready) {
            tts.voices?.firstOrNull { it.locale.language == locale.language }?.let { tts.voice = it }
            onStatus("เสียง${if (mode == VoiceMode.THAI) "ภาษาไทย" else "ภาษาอังกฤษ"}พร้อม")
        } else onStatus("ไม่พบเสียงที่เลือกในเครื่อง")
    }

    fun speakChainage(meters: Int): String {
        if (mode == VoiceMode.OFF) return ""
        val km = meters / 1000
        val remainder = meters % 1000
        val text = if (mode == VoiceMode.THAI) {
            "กิโลเมตร${ThaiDistanceWords.number(km)} ${ThaiDistanceWords.number(remainder)}เมตร"
        } else {
            "Kilometer $km plus ${remainder.toString().padStart(3, '0')}"
        }
        if (ready) speakNow(text) else {
            pendingText = text
            onStatus("กำลังโหลดเสียง")
        }
        return text
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trip-distance-${System.currentTimeMillis()}")
    }

    fun shutdown() = tts.shutdown()

    private fun localeFor(value: VoiceMode) = if (value == VoiceMode.ENGLISH) Locale.US else Locale("th", "TH")
}

object ThaiDistanceWords {
    private val digits = arrayOf("ศูนย์", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า")
    private val units = arrayOf("", "สิบ", "ร้อย", "พัน", "หมื่น", "แสน")

    fun number(value: Int): String {
        require(value >= 0)
        if (value == 0) return digits[0]
        if (value >= 1_000_000) {
            val high = value / 1_000_000
            val low = value % 1_000_000
            return number(high) + "ล้าน" + if (low == 0) "" else numberBelowMillion(low)
        }
        return numberBelowMillion(value)
    }

    private fun numberBelowMillion(value: Int): String {
        val text = StringBuilder()
        for (position in 5 downTo 0) {
            val power = 10.0.pow(position).toInt()
            val digit = (value / power) % 10
            if (digit == 0) continue
            when (position) {
                1 -> text.append(if (digit == 1) "" else if (digit == 2) "ยี่" else digits[digit]).append("สิบ")
                0 -> text.append(if (digit == 1 && value >= 10) "เอ็ด" else digits[digit])
                else -> text.append(digits[digit]).append(units[position])
            }
        }
        return text.toString()
    }
}

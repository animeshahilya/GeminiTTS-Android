package com.example.genaittsdemo.data

import android.speech.tts.Voice
import java.util.Locale

/**
 * Official Voice Category according to Google's documentation:
 * 1. [GEMINI_STUDIO]: Official Google Gemini API & AI Studio prebuilt voice library (mythological personas).
 * 2. [GEMINI_LIVE]: Official Google Gemini Live conversational voice lineup in Android (celestial/constellation personas).
 */
enum class VoiceCategory(val label: String) {
    GEMINI_STUDIO("Gemini Studio"),
    GEMINI_LIVE("Gemini Live")
}

data class GeminiVoiceProfile(
    val id: String,
    val name: String,
    val gender: String, // "FEMALE", "MALE"
    val style: String,
    val description: String,
    val category: VoiceCategory,
    val locale: Locale = Locale.US,
    val pitchMultiplier: Float = 1.0f
) {
    fun toAospVoice(): Voice {
        return Voice(
            id,
            locale,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_VERY_LOW,
            false,
            setOf("gemini", "ondevice", "npu", category.name.lowercase(), gender.lowercase())
        )
    }
}

/**
 * Authoritative registry of official Google Gemini Text-to-Speech voices
 * documented across Google AI Studio, Vertex AI, and the Gemini Live Android app.
 */
object GeminiVoiceCatalog {

    val ALL_VOICES: List<GeminiVoiceProfile> = listOf(
        // === Official Google Gemini API / Studio Voices (Mythological Series) ===
        GeminiVoiceProfile(
            id = "en-us-gemini-aoede",
            name = "Aoede",
            gender = "FEMALE",
            style = "Breezy & Natural",
            description = "Google's flagship conversational voice; relaxed, smooth, and ideal for long-form reading and audiobooks.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 1.0f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-kore",
            name = "Kore",
            gender = "FEMALE",
            style = "Firm & Confident",
            description = "Direct, articulate, and authoritative voice; excellent for screen reader guidance and business applications.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 0.98f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-puck",
            name = "Puck",
            gender = "MALE",
            style = "Upbeat & Lively",
            description = "Energetic, engaging, and friendly male voice with an upbeat conversational cadence.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 1.02f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-charon",
            name = "Charon",
            gender = "MALE",
            style = "Informative & Calm",
            description = "Deep, deliberate, and professional tone; optimal for news, documentation, and technical content.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 0.92f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-fenrir",
            name = "Fenrir",
            gender = "MALE",
            style = "Passionate & Dynamic",
            description = "Excitable and highly expressive male delivery with rich acoustic dynamics.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 0.95f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-leda",
            name = "Leda",
            gender = "FEMALE",
            style = "Youthful & Energetic",
            description = "Vibrant, bright female voice with quick response pacing.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 1.05f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-orus",
            name = "Orus",
            gender = "MALE",
            style = "Calm & Authoritative",
            description = "Steady, warm, and balanced baritone voice.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 0.90f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-zephyr",
            name = "Zephyr",
            gender = "FEMALE",
            style = "Bright & Clear",
            description = "Crisp articulation with high phonetic intelligibility.",
            category = VoiceCategory.GEMINI_STUDIO,
            pitchMultiplier = 1.03f
        ),

        // === Official Google Gemini Live Lineup (Constellation / Celestial Series) ===
        GeminiVoiceProfile(
            id = "en-us-gemini-nova",
            name = "Nova",
            gender = "FEMALE",
            style = "Calm & Mid-range",
            description = "Balanced, natural daily companion voice from Gemini Live.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 1.0f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-orion",
            name = "Orion",
            gender = "MALE",
            style = "Bright & Deeper Pitch",
            description = "Resonant, deep male timbre with clear articulation.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 0.88f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-capella",
            name = "Capella",
            gender = "FEMALE",
            style = "Serene & Articulate",
            description = "Gentle, higher-pitch female delivery with serene pacing.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 1.06f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-pegasus",
            name = "Pegasus",
            gender = "MALE",
            style = "Engaged & Deeper Pitch",
            description = "Focused, assertive, and direct male voice.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 0.91f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-lyra",
            name = "Lyra",
            gender = "FEMALE",
            style = "Bright & Melodic",
            description = "Light, pleasant, and highly responsive tone.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 1.04f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-ursa",
            name = "Ursa",
            gender = "FEMALE",
            style = "Engaged & Mid-range",
            description = "Warm, grounded, and attentive delivery.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 0.99f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-dipper",
            name = "Dipper",
            gender = "MALE",
            style = "Casual & Grounded",
            description = "Relaxed and conversational male tone.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 0.94f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-eclipse",
            name = "Eclipse",
            gender = "FEMALE",
            style = "Energetic & Bold",
            description = "High energy, commanding female presence.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 1.01f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-orbit",
            name = "Orbit",
            gender = "MALE",
            style = "Energetic & Deeper Pitch",
            description = "Fast-paced, dynamic male delivery with rich low-end.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 0.89f
        ),
        GeminiVoiceProfile(
            id = "en-us-gemini-vega",
            name = "Vega",
            gender = "FEMALE",
            style = "Bright & Higher Pitch",
            description = "Crisp, airy, and enthusiastic female voice.",
            category = VoiceCategory.GEMINI_LIVE,
            pitchMultiplier = 1.08f
        )
    )

    fun findById(id: String?): GeminiVoiceProfile? {
        if (id == null) return null
        return ALL_VOICES.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: ALL_VOICES.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }

    fun findByName(name: String?): GeminiVoiceProfile? {
        if (name == null) return null
        return ALL_VOICES.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: ALL_VOICES.firstOrNull { it.id.contains(name, ignoreCase = true) }
    }

    fun getDefaultVoice(): GeminiVoiceProfile = ALL_VOICES.first() // Aoede

    fun getDefaultVoiceForGender(gender: String): GeminiVoiceProfile {
        return ALL_VOICES.firstOrNull { it.gender.equals(gender, ignoreCase = true) }
            ?: getDefaultVoice()
    }
}

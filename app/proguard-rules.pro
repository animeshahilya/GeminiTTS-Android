# The TTS service and activities are kept through the manifest; ML Kit ships its own rules.

# Drop debug and verbose logging from release builds (per-utterance timing lines).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# The default ViewModel factory creates AndroidViewModels by reflection through this constructor.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# ML Kit GenAI (alpha) looks parts of itself up by reflection and its bundled rules don't cover it:
# shrinking made SpeechSynthesis.getClient() fail with a NullPointerException. Keep it whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }

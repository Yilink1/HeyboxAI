# Xposed & Hook Framework Entry Points
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }
-keep class io.github.yilink1.heyboxai.hook.** { *; }
-keepclassmembers class io.github.yilink1.heyboxai.hook.** { *; }

# Content Provider & Configuration
-keep class io.github.yilink1.heyboxai.config.** { *; }
-keep class io.github.yilink1.heyboxai.ui.SummaryHistoryManager** { *; }

# Serialization Models (Gson & JSON)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class io.github.yilink1.heyboxai.ai.AiClient$** { *; }
-dontwarn com.google.gson.**

# OkHttp & SSE
-dontwarn okhttp3.**
-dontwarn okio.**

# Markwon Rich Text
-dontwarn io.noties.markwon.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

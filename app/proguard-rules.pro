# Standard shrinking + name-obfuscation (this is what "obfuscation" means for a normal
# Android app — it's Google's own R8 tool, not a bespoke anti-analysis layer).

# WorkManager workers are found by class name at runtime — keep them nameable.
-keep class com.anil.igbizlogger.DailyExportWorker { *; }
-keep class com.anil.igbizlogger.DeleteLocalFileWorker { *; }

# Google API client libraries do some reflection-based (de)serialization.
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**

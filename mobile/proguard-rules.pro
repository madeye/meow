# Navigation Compose resolves type-safe routes reflectively through
# kotlinx.serialization. R8 has no reference to the generated serializers, so
# without this every navigate() throws — and only in minified builds.
-keep,includedescriptorclasses class io.github.madeye.meow.ui.nav.** { *; }
-keepclassmembers class io.github.madeye.meow.ui.nav.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Sora Editor + TextMate (tm4e) — Gson reflects on grammar/theme model classes
# loaded from textmate/languages.json and grammar JSON files. R8 strips
# concrete subclasses since they're never explicitly referenced from code.
-keep class io.github.rosemoe.sora.** { *; }
-keep interface io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep interface org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**

# Gson keep rules — needed for any class deserialized via reflection.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# JDT annotations referenced by tm4e at runtime via reflection — strip
# warnings about the missing optional dep.
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.osgi.framework.**
-dontwarn kotlin.Cloneable$DefaultImpls

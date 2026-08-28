# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ===== kotlinx.serialization =====
# @Serializable 类由编译器生成 *$serializer 类，R8 混淆/裁剪会破坏序列化，必须 keep
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.magicnote.mgxd.**$$serializer { *; }
-keepclassmembers class com.magicnote.mgxd.** { *** Companion; }
-keepclasseswithmembers class com.magicnote.mgxd.** { kotlinx.serialization.KSerializer serializer(...); }

# ===== Room =====
# Room 自带 consumer rules；实体类保持可反射访问（部分 DAO 泛型/转换器需要）
-keep class com.magicnote.mgxd.data.db.** { *; }

# ===== java.time / desugaring =====
-dontwarn java.time.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
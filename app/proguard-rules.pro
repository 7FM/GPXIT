# XmlPullParser conflict between Android framework and library
-dontwarn org.xmlpull.v1.**
-dontwarn org.kxml2.**
-keep class org.xmlpull.v1.** { *; }
-keep class org.kxml2.** { *; }

# public-transport-enabler uses reflection for JSON parsing
-keep class de.schildbach.pte.** { *; }
-keep class de.schildbach.pte.dto.** { *; }

# osmdroid
-keep class org.osmdroid.** { *; }

# GPX parser
-keep class io.ticofab.androidgpxparser.** { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

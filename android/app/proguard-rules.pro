# Bouncy Castle: R8 no ve los proveedores que se cargan por reflexión.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization genera los serializadores con nombres derivados de la clase.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.marcmayol.dracpaste.** {
    *** Companion;
}
-keepclasseswithmembers class com.marcmayol.dracpaste.** {
    kotlinx.serialization.KSerializer serializer(...);
}

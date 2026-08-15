# Bouncy Castle.
#
# DracPaste usa la API de **bajo nivel** (`org.bouncycastle.crypto.*` y
# `org.bouncycastle.math.ec.rfc7748.X25519`), a la que llama directamente. R8 puede
# renombrarla sin problema, porque no hay reflexión de por medio: eso es precisamente por
# lo que se eligió esa API y no el proveedor JCE (ver docs/decisions.md D-003).
#
# Aquí había un `-keep class org.bouncycastle.jcajce.provider.**` heredado de las reglas
# "por si acaso" del esqueleto. Conservaba **4.050 clases** del proveedor JCE que la app
# no toca, medio megabyte de código muerto en el APK. Se quita: si algún día se usara el
# proveedor —que se carga por su nombre y sí necesitaría reglas—, habría que volver a
# ponerlo, pero mientras no se use, mantenerlo solo engorda la descarga.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# kotlinx.serialization genera los serializadores a partir del nombre de la clase: sin
# estas reglas, R8 los renombra y el JSON del protocolo deja de poder decodificarse.
# Es el fallo que se manifestaría solo en la versión publicada.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.marcmayol.dracpaste.** {
    *** Companion;
}
-keepclasseswithmembers class com.marcmayol.dracpaste.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.marcmayol.dracpaste.**$$serializer { *; }

# Los nombres de los campos del protocolo (t, device_id, timestamp_ms...) vienen de
# @SerialName, que es una anotación en tiempo de compilación, así que no hace falta
# conservarlos. Sí las clases de datos que los llevan, por lo de arriba.

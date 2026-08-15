# Reglas solo para el APK de tests instrumentados.
#
# Los tests corren sobre el build de publicación (testBuildType = "release") para
# comprobar que R8 no rompe la criptografía. El efecto secundario es que el propio APK de
# test también se minifica, y las librerías de AndroidX Test arrastran referencias a
# clases que no están en el classpath: anotaciones de Error Prone y un adaptador de
# corrutinas que solo existe si se usa esa parte de la API.
#
# Son referencias de las librerías de prueba, no de DracPaste: silenciarlas no relaja
# ninguna regla de la app.

-dontwarn androidx.concurrent.futures.SuspendToFutureAdapter
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.element.**
-dontwarn org.junit.internal.**

# Los tests se localizan por reflexión: sin esto, el runner no encontraría ninguno.
-keep class com.marcmayol.dracpaste.**Test { *; }
-keep class androidx.test.** { *; }
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * {
    @org.junit.Test *;
    @org.junit.Before *;
    @org.junit.After *;
}

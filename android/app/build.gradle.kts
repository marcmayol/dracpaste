import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Se usa kotlinx.serialization en vez de org.json para persistir: org.json existe
    // en el SDK pero en los tests unitarios de JVM es un stub que lanza excepciones,
    // así que la lógica de emparejados no se podría probar sin emulador.
    alias(libs.plugins.kotlin.serialization)
}

// La configuración de firma vive fuera del repositorio: keystore.properties está en el
// .gitignore y la keystore, en la carpeta del usuario.
//
// Orden: fichero → variable de entorno → debug.keystore. El último escalón es lo que
// permite que cualquiera clone el proyecto y compile sin tener las claves; que eso NO
// acabe publicado por accidente lo garantiza scripts/publicar_release.py, que aborta si
// el APK viene firmado con "CN=Android Debug".
val ficheroDeFirma = rootProject.file("keystore.properties")
val propiedadesDeFirma = Properties().apply {
    if (ficheroDeFirma.exists()) {
        FileInputStream(ficheroDeFirma).use { load(it) }
    }
}

fun datoFirma(clave: String, variableEntorno: String): String? =
    (propiedadesDeFirma[clave] as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(variableEntorno)?.takeIf { it.isNotBlank() }

val almacenDeFirma = datoFirma("storeFile", "DRACPASTE_STORE_FILE")
val hayKeystoreDePublicacion = almacenDeFirma != null

android {
    namespace = "com.marcmayol.dracpaste"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marcmayol.dracpaste"
        // Android 10. Por debajo, el modelo de portapapeles es otro y la asimetría de
        // diseño del plan (§1) no tendría sentido.
        minSdk = 29
        targetSdk = 35
        // El versionCode sube de uno en uno y es lo único que mira el actualizador.
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Los tests instrumentados corren sobre el build de depuración. Se intentó ponerlos
    // sobre el de publicación para comprobar de paso que R8 no rompía nada, pero al
    // minificar también el APK de test, el propio runner de AndroidX se queda sin
    // métodos que busca por reflexión y la instrumentación no llega ni a arrancar.
    //
    // Lo que aportan estos tests es el **Android Keystore de verdad**, que no existe en
    // el JVM. Que R8 no rompe el cifrado se comprueba de otra forma, más directa:
    // emparejando el APK de publicación contra el servidor real
    // (ver PROGRESS.md, Fase 6).

    signingConfigs {
        create("release") {
            if (hayKeystoreDePublicacion) {
                storeFile = file(almacenDeFirma!!)
                storePassword = datoFirma("storePassword", "DRACPASTE_STORE_PASSWORD")
                keyAlias = datoFirma("keyAlias", "DRACPASTE_KEY_ALIAS")
                keyPassword = datoFirma("keyPassword", "DRACPASTE_KEY_PASSWORD")
            } else {
                // Sin claves, se firma con la de depuración: cualquiera puede compilar,
                // pero publicar así lo impide el script de publicación.
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }

            // v2 y v3 además de v1: sin ellas, Android 11+ rechaza el APK.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            signingConfig = signingConfigs.getByName("release")

            // El APK de tests instrumentados también se minifica, porque corre sobre este
            // mismo build. Estas reglas son solo para él.
            testProguardFiles("proguard-test.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // El actualizador compara la versión remota contra BuildConfig.VERSION_CODE.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":protocolo"))

    // Auto-actualización fuera de Play Store, el mismo módulo que el resto de sus apps.
    implementation(project(":actualizador"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Escáner del QR de emparejamiento. Se usa el modelo de códigos de barras embebido
    // en el APK, no el de Google Play Services: la app no debe depender de descargar
    // nada en tiempo de ejecución ni de que Play Services esté instalado.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

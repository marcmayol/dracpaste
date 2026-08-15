plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Módulo JVM puro. No depende del SDK de Android ni puede hacerlo: esa es su razón de
// ser (docs/decisions.md D-002). Todo lo que necesite Android vive en :app, detrás de
// las interfaces que este módulo declara.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Imprime los valores derivados de los vectores de prueba, para poder fijarlos en
// docs/protocol.md §7 y en los tests de los dos lados.
tasks.register<JavaExec>("imprimirVectores") {
    group = "verification"
    description = "Imprime los vectores de prueba derivados del protocolo"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.marcmayol.dracpaste.protocolo.cripto.ImprimirVectores")
}

// Hace de móvil contra el servidor real de Windows. Lo lanza scripts/prueba-cruzada.ps1:
// es la única forma de demostrar que Bouncy Castle y libsodium se entienden por un socket
// de verdad, y no solo sobre vectores copiados en los dos lados.
tasks.register<JavaExec>("clienteDePrueba") {
    group = "verification"
    description = "Cliente Kotlin que empareja y habla con el servidor C#"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.marcmayol.dracpaste.protocolo.ClienteDePrueba")
}

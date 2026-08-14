pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DracPaste"

// Protocolo, cifrado, anti-eco y máquina de estados. Es un módulo JVM puro a propósito:
// sin el SDK de Android en el classpath, el compilador impide que importe android.*, y
// sus tests corren en segundos sin emulador ni móvil (docs/decisions.md D-002).
include(":protocolo")

// Servicio, notificación, portapapeles, NSD, Keystore y pantallas Compose.
include(":app")

# DracPaste

Portapapeles compartido entre Android y Windows, **sin nube, sin cuenta y sin telemetría**.
Los dispositivos hablan directamente por la red local, cifrados de extremo a extremo, y la
identidad es el propio par de dispositivos emparejados: no hay servidor de nadie en medio.

> Estado: en desarrollo. Ver [`PROGRESS.md`](PROGRESS.md) para saber qué funciona ya y qué
> pruebas quedan pendientes de hacer con hardware real.

## Cómo funciona

| Dirección | Comportamiento |
|---|---|
| **PC → móvil** | Automático. Copias en el PC y ya está en el portapapeles del móvil. |
| **Móvil → PC** | Un gesto: el botón de la notificación, o compartir a DracPaste desde cualquier app. |

La asimetría no es pereza. Desde Android 10 una aplicación **no puede leer el portapapeles**
salvo que tenga el foco de pantalla o sea el teclado del sistema. DracPaste no usa `READ_LOGS`,
ni root, ni ADB, ni se convierte en tu teclado: cuando pulsas el botón, abre durante unos
milisegundos una ventana invisible que sí tiene el foco, lee, envía y se cierra.

## Estructura

```
android/    Proyecto Gradle. Módulo :protocolo (JVM puro) + :app (Android).
windows/    Solución .NET 8. DracPaste.Protocolo + .Tests + .Bandeja (app de bandeja).
docs/       protocol.md (especificación normativa), decisions.md, guías de usuario.
PLAN.md     Documento de diseño: visión, decisiones cerradas, fases y riesgos.
PROGRESS.md Estado por fases y pruebas manuales pendientes.
```

## Compilar

### Requisitos

| | Versión | Nota |
|---|---|---|
| JDK | 17 | Para Gradle y el módulo JVM |
| Android SDK | platform 35, build-tools 35 | `ANDROID_HOME` o `android/local.properties` |
| .NET SDK | 8.0 | Solo el *runtime* no basta |

### Android

```powershell
cd android
.\gradlew.bat :protocolo:test          # tests del protocolo, sin emulador ni móvil
.\gradlew.bat :app:assembleDebug       # APK de depuración
```

El APK sale en `android/app/build/outputs/apk/debug/`.

Si el SDK de Android no está en `ANDROID_HOME`, crea `android/local.properties`:

```
sdk.dir=C\:\\ruta\\a\\Android\\Sdk
```

### Windows

```powershell
cd windows
dotnet build DracPaste.sln
dotnet test DracPaste.Protocolo.Tests    # tests del protocolo
dotnet run --project DracPaste.Bandeja   # arranca el icono de bandeja
```

**En esta máquina en concreto**: el `dotnet` del `PATH` (`C:\Program Files\dotnet`) trae
solo el *runtime*. El SDK está instalado para el usuario, así que hay que anteponerlo:

```powershell
$env:PATH = "$env:USERPROFILE\.dotnet;$env:PATH"
```

El motivo está en [`docs/decisions.md`](docs/decisions.md) D-001.

### Regenerar el icono de Windows

El `.ico` no se versiona como binario opaco: se genera desde código.

```powershell
powershell -ExecutionPolicy Bypass -File windows\Recursos-fuente\generar-icono.ps1
```

## Privacidad

- **Nada sale de la red local.** v1 no tiene relay ni servidores; si el PC y el móvil no
  están en la misma red, no hay sincronización (y la app lo dice).
- **Cifrado de extremo a extremo siempre**, incluso dentro de la LAN: X25519 para acordar
  la clave, ChaCha20-Poly1305 para los mensajes, claves nuevas en cada conexión.
- **Una clave por pareja de dispositivos.** Desemparejar o comprometer un PC no afecta a
  los demás.
- **Los clips marcados como sensibles no se sincronizan nunca.** Cuando un gestor de
  contraseñas copia algo, marca el clip con `EXTRA_IS_SENSITIVE`; DracPaste lo respeta y
  esa contraseña no viaja a ninguna parte.
- **Cero analíticas, cero telemetría, cero cuentas.** No hay nada que registrar porque no
  hay a dónde enviarlo.
- **El identificador de dispositivo es aleatorio**, generado en la instalación. No deriva
  del IMEI, del Android ID ni de ningún número de serie.

## Licencia

Pendiente de decidir.

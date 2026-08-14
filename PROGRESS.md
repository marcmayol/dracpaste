# Estado del proyecto

Una entrada por fase, escrita al cerrarla. Cada una dice **qué se hizo**, **qué se verificó
automáticamente** y **qué queda pendiente de probar con hardware real**.

Los criterios de aceptación de `PLAN.md` que exigen un móvil físico, WiFi real, suspender el
PC o medir latencias **no se marcan como cumplidos aquí**: se acumulan en la sección
"Pruebas manuales pendientes" de cada fase, con pasos exactos y resultado esperado.

---

## Fase 0 · Esqueleto — CERRADA (2026-08-14)

### Qué se hizo

- Monorepo en `C:\Users\marcm\DracPaste` con `git init` (rama `main`) y la estructura del
  plan: `/android`, `/windows`, `/docs`, más `PLAN.md`, `PROGRESS.md`, `README.md`.
- **Android**: proyecto Gradle con dos módulos.
  - `:protocolo` — módulo **JVM puro** (Kotlin 2.0.21, toolchain 17). Sin el SDK de Android
    en el classpath, así que el compilador impide que importe `android.*`. Sus tests corren
    en el JVM local, sin emulador.
  - `:app` — aplicación Android (AGP 8.9.1, `minSdk 29`, `targetSdk 35`, Compose), con el
    manifiesto y los permisos del plan §3.1 ya declarados, tema translúcido para la futura
    activity de captura, e icono adaptativo.
- **Windows**: solución .NET 8 con tres proyectos.
  - `DracPaste.Protocolo` — biblioteca `net8.0` con NSec (libsodium).
  - `DracPaste.Protocolo.Tests` — xUnit.
  - `DracPaste.Bandeja` — `net8.0-windows` WinForms, **sin ventana principal**: icono de
    bandeja con menú (estado, emparejar, ajustes, salir) y mutex de instancia única.
- **`docs/protocol.md`**: la sección 4 de `PLAN.md` desarrollada hasta el detalle de bytes
  —framing, derivación de claves, nonces, formato de cada mensaje, anti-eco, máquina de
  estados y registros TXT de mDNS— para que dos implementaciones en lenguajes distintos no
  puedan interpretarla de dos maneras.
- **`docs/decisions.md`**: seis decisiones registradas (D-001 a D-006), incluidas las dos
  desviaciones respecto al plan y su justificación.
- **`README.md`** con instrucciones de build para ambos lados.
- El icono de Windows se genera desde código (`windows/Recursos-fuente/generar-icono.ps1`)
  en vez de versionar un binario opaco.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:build` | Correcto |
| `gradlew :app:assembleDebug` | Correcto, APK generado |
| `dotnet build DracPaste.sln` | Correcto, 0 advertencias, 0 errores |

### Decisiones tomadas sobre la marcha

- **D-001 · SDK de .NET**: la máquina tenía runtime pero no SDK, así que el lado Windows no
  compilaba. Se instaló el SDK 8.0.424 **per-user** (`dotnet-install.ps1 -NoPath` en
  `~/.dotnet`), sin permisos de administrador y sin tocar la configuración del equipo.
- **D-002 · `:protocolo` es JVM puro**, no una librería Android. Es lo que permite probar
  cifrado, framing y máquina de estados sin un dispositivo, que es justo lo que el modo
  autónomo necesita.
- **D-003 · Desviación del plan en criptografía**: Bouncy Castle en Kotlin en lugar de
  `lazysodium-android` (NSec sí se usa en C#, como pedía el plan). Motivo: lazysodium es
  código nativo por ABI y obligaría a que todos los tests de cifrado fueran instrumentados.
  X25519 y ChaCha20-Poly1305 son estándares RFC, no formatos de libsodium, así que la
  interoperabilidad se mantiene y se demuestra con vectores de prueba compartidos.
- **D-004 · Mensajes en JSON** dentro del sobre cifrado (`kotlinx-serialization` en Kotlin,
  `System.Text.Json` en C#).
- **NSec 24.4.0**, no la última (26.4.0): esa exige `net9.0` y el plan fija .NET 8.

### Pruebas manuales pendientes

Ninguna. Los criterios de aceptación de esta fase ("ambos proyectos compilan; README con
instrucciones de build") se verifican por completo con las builds de arriba.

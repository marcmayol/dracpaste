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

---

## Fase 1 · Túnel (descubrimiento + socket + cifrado) — CERRADA (2026-08-15)

### Qué se hizo

**El protocolo, entero y por duplicado.** `docs/protocol.md` §1–§6 está implementado en
Kotlin (`:protocolo`, con Bouncy Castle) y en C# (`DracPaste.Protocolo`, con
NSec/libsodium):

- **Criptografía**: X25519, HKDF-SHA256 y ChaCha20-Poly1305. Las claves públicas y el
  secreto compartido coinciden con los vectores del RFC 7748.
- **Derivación**: clave de par ordenando las dos públicas (mismo resultado en los dos
  lados, sin depender de quién es cliente) y **claves de sesión nuevas en cada conexión**,
  derivadas de dos retos frescos. Es lo que hace seguro que los contadores de nonce
  empiecen en 0 tras cada reconexión, y hay muchas: cambios de red, suspensión, Doze.
- **Framing** `[uint32 BE][payload]`, validando la longitud antes de reservar memoria.
- **Sobre cifrado** con contador por dirección y rechazo de contadores repetidos o hacia
  atrás.
- **Mensajes** JSON con discriminador `t`; un tipo desconocido se ignora en vez de cortar
  la sesión.
- **Anti-eco** por `origin_id` con ventana que caduca.
- **Máquina de estados** con backoff exponencial 1 s → 30 s.
- **Handshake** y **emparejamiento** completos, en los dos lenguajes.

**Windows** (`DracPaste.Bandeja`), ya funcional como app de bandeja:

- `Identidad`: par X25519 y `device_id` persistidos, con la privada cifrada por **DPAPI**
  en el ámbito del usuario actual.
- `RegistroEmparejados`: guarda solo claves públicas; la de par se recalcula al vuelo.
- `GestorTokens`: tokens de un solo uso que caducan a los dos minutos.
- `ServidorDracPaste`: `TcpListener` que distingue emparejamiento de sesión por el primer
  frame, atiende cada conexión aparte y sustituye la sesión al reconectar.
- `AnuncioMdns`: publica `_dracpaste._tcp` con el `device_id` en los TXT y sabe
  reanunciarse al despertar de suspensión (`SystemEvents.PowerModeChanged`).
- Ventana de emparejamiento con el JSON del QR en texto copiable (el QR gráfico es de la
  Fase 4, tal como marca el plan).

**Android** (`:app`):

- `AlmacenIdentidad`: el Keystore no puede custodiar una clave X25519 arbitraria, así que
  genera una AES-GCM que nunca sale del hardware seguro y con ella se cifra la privada.
  Una copia del fichero no sirve en otro móvil.
- `RegistroPcs`: PCs emparejados y **selector de PC activo**, con relevo automático al
  desemparejar el activo.
- `DescubridorNsd`: descubrimiento mDNS que solo atiende el anuncio cuyo `device_id`
  coincide con el PC activo.
- `ClienteDracPaste`: conexión, handshake, PING/PONG, y reconexión en paralelo (última IP
  conocida con backoff + mDNS por si cambió).
- `ServicioDracPaste`: foreground service `connectedDevice` con notificación de estado.
- Pantalla para emparejar pegando el texto del PC.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:test` | 92 tests, 0 fallos |
| `gradlew :app:testDebugUnitTest` | 15 tests, 0 fallos |
| `gradlew :app:assembleDebug` | Correcto |
| `dotnet test DracPaste.Protocolo.Tests` | 77 tests, 0 fallos |
| `dotnet test DracPaste.Bandeja.Tests` | 32 tests, 0 fallos |
| Solución .NET completa, 5 vueltas seguidas | Sin intermitencias |

**216 tests en total**, ninguno necesita hardware. Los que más cubren de esta fase:

- **Interoperabilidad Kotlin ↔ C# sin conectar nada**: los mismos vectores de
  `docs/protocol.md` §7 se comprueban en los dos lados. Además, un sobre sellado en Kotlin
  se abre en C# y su JSON se decodifica igual. Si una implementación se desviara, el test
  de la otra se pondría en rojo.
- **El criterio de Wireshark, automatizado**: un test comprueba que los bytes que salen
  del socket no contienen ni el texto del clip ni la palabra `CLIP`.
- **Handshake y emparejamiento sobre loopback** en ambos lenguajes, incluidos los casos
  del impostor: un PC con otra clave, un PC que no es el activo, un móvil no emparejado y
  un token inválido o ya usado.
- **El servidor real** (`ServidorDracPaste` con su `TcpListener`) atendido por un cliente
  que hace exactamente lo que hará el móvil: emparejarse, abrir sesión, mandar y recibir
  clips, responder al PING y sobrevivir a que alguien le escriba basura en el puerto.

### Dos fallos reales encontrados por los tests

**1. El emparejamiento se daba por bueno demasiado pronto** (arreglado en el protocolo).

El `PAIR_ACK` es el último mensaje del emparejamiento, así que su emisor —el móvil— no
tenía forma de saber si había llegado: se declaraba emparejado antes de que el PC hubiera
guardado nada. Un PC que se quedara sin disco, sin permisos o sin red justo ahí dejaba al
usuario con el emparejamiento hecho en una pantalla y sin hacer en la otra, y en cada
conexión posterior el PC lo habría rechazado sin explicación.

Ahora el PC cierra la conexión **solo después de guardar**, y el móvil espera ese cierre
como acuse de recibo. Documentado primero en `docs/protocol.md` §3.2 (pasos 5 y 6) y
después en los dos lados, en el mismo cambio, como exige el plan §8.

Lo destapó un test que fallaba una vez de cada seis, no una revisión del código.

**2. `File.renameTo` no sobrescribe** (arreglado en Android).

El registro de PCs y la identidad se escriben a un temporal y se mueven encima, para que
un corte de batería no deje un fichero truncado. Pero `File.renameTo` **no reemplaza un
fichero existente**: en Windows falla devolviendo `false`, sin excepción. El resultado era
que el registro se quedaba congelado en su primera versión —cambiar de PC activo o
desemparejar no persistía— y nada avisaba.

En Android habría funcionado por casualidad, porque allí `rename` es la llamada POSIX, que
sí sobrescribe. Se ha cambiado a `Files.move` con `REPLACE_EXISTING` y `ATOMIC_MOVE`, que
se comporta igual en los dos sistemas.

### Decisiones tomadas sobre la marcha

- **`:app` usa kotlinx.serialization en vez de `org.json`**. `org.json` viene en el SDK,
  pero en los tests unitarios de JVM es un stub que lanza excepciones, así que la lógica
  del registro de PCs no se habría podido probar sin emulador. Con el cambio, `RegistroPcs`
  recibe un `File` en lugar de un `Context` y sus 15 tests corren en el JVM.
- **`ServidorDracPaste.Arrancar` admite un puerto**: los tests usan el 0 (efímero) para no
  competir por el puerto fijo del protocolo cuando corren en paralelo.
- **NSec 24.4.0**, no la 26.x: esa exige `net9.0` y el plan fija .NET 8.

### Pruebas manuales pendientes

Los criterios de aceptación de la Fase 1 que **no** se pueden verificar sin hardware. Lo
que ya está cubierto automáticamente se indica al final de cada uno.

**M1.1 · El texto viaja en las dos direcciones entre móvil y PC reales**

1. Arrancar `DracPaste.exe` en el PC (bandeja) y conectar el PC al WiFi de casa.
2. Instalar el APK de depuración en el móvil y conectarlo **al mismo WiFi**.
3. En el PC: clic derecho en el icono de la bandeja → «Emparejar un móvil…» → «Copiar al
   portapapeles» y pasar ese texto al móvil (por ejemplo, por Telegram con uno mismo).
4. En el móvil: abrir DracPaste, pegar el texto y pulsar «Emparejar».

*Resultado esperado*: el móvil muestra «Emparejado con [nombre del PC]» y una huella tipo
`A3F2-9C71`; el PC muestra un globo con **la misma huella**. En menos de 30 segundos, la
notificación del móvil pasa a «Conectado con [PC]».

**M1.2 · El descubrimiento por mDNS funciona sin escribir ninguna IP**

1. Con el par ya emparejado, cerrar DracPaste en el PC y volver a abrirlo.
2. No tocar el móvil.

*Resultado esperado*: la notificación del móvil vuelve a «Conectado con [PC]» sola, sin
que nadie escriba una dirección. Si el router ha cambiado la IP del PC, debe encontrarlo
igualmente.

**M1.3 · El payload es ilegible en la red**

1. Con el par conectado, abrir Wireshark en el PC y filtrar `tcp.port == 47653`.
2. Copiar en el PC un texto reconocible, por ejemplo `TEXTO-DE-PRUEBA-DRACPASTE`.

*Resultado esperado*: en el flujo TCP no aparece esa cadena, ni la palabra `CLIP`, ni nada
legible más allá de los primeros bytes del handshake (`HELLO` y `SERVER_HELLO`, que van en
claro por diseño y solo contienen un `device_id` y un reto aleatorio).

*Ya cubierto automáticamente*: un test comprueba lo mismo sobre los bytes que salen del
socket. La prueba manual añade la confirmación de que no hay nada más en el cable.

**M1.4 · Reconexión automática tras cortar el WiFi**

1. Con el par conectado, activar el modo avión en el móvil.
2. Esperar a que la notificación pase a «Reconectando».
3. Desactivar el modo avión.

*Resultado esperado*: la notificación vuelve a «Conectado con [PC]» sin tocar nada. Debe
tardar menos de 10 segundos desde que el WiFi vuelve.

**M1.5 · Un token de emparejamiento caducado se rechaza**

1. En el PC, abrir la ventana de emparejamiento y **esperar más de dos minutos**.
2. Intentar emparejar el móvil con ese texto.

*Resultado esperado*: el móvil muestra un fallo y **no** queda emparejado; el PC no añade
nada a su lista.

*Ya cubierto automáticamente*: la caducidad y el uso único del token tienen tests. Esta
prueba confirma que el mensaje que ve el usuario es comprensible.

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

---

## Fase 2 · Windows → Android completo — CERRADA (2026-08-15)

### Qué se hizo

**Windows: el vigilante del portapapeles** (`VigilanteDelPortapapeles`). Usa
`AddClipboardFormatListener` sobre una ventana *message-only*, así que el sistema avisa de
cada copia en vez de tener la app preguntando: sin polling no hay consumo cuando nadie
copia nada, que es casi todo el tiempo.

Lleva **dos protecciones contra el eco**, no una:

1. Un **formato de portapapeles propio** (`DracPasteOrigin`) que se pega a todo lo que
   escribe la app. Si un aviso lo trae, el cambio es obra nuestra y se descarta.
2. El **texto del último clip recibido**, por si el formato propio se pierde: algunos
   gestores de portapapeles copian solo el texto y tiran el resto.

Son dos porque, si falla el único que hay, el resultado no es un fallo silencioso sino un
bucle infinito visible entre los dos aparatos. Además hay **debounce de 100 ms**, porque
Office y otras aplicaciones disparan un aviso por cada formato que publican y un solo
Ctrl+C llegaría tres o cuatro veces.

También: reintentos al escribir (el portapapeles puede estar tomado por otra aplicación
unos milisegundos), aviso al usuario cuando una copia pasa del máximo de 256 KiB, y envío
en segundo plano para no congelar el escritorio mientras dura una escritura de red.

**Android: la escritura y su plan B** (`GestorPortapapeles`, `ActividadPegar`).

- El servicio escribe en el portapapeles cada clip que llega del PC.
- Si el fabricante lo impide desde segundo plano —pasa en algunos OEM—, **el clip no se
  pierde**: se guarda y la notificación cambia a «Clip recibido, toca para pegarlo» con un
  botón que abre una activity invisible y lo escribe con el foco puesto.
- El `origin_id` se marca **antes** de escribir. Si se marcara después, el aviso del
  propio portapapeles podría llegar en medio y el clip saldría de vuelta hacia el PC.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:test` | 99 tests, 0 fallos |
| `gradlew :app:testDebugUnitTest` | 15 tests, 0 fallos |
| `gradlew :app:assembleDebug` | Correcto |
| `dotnet test` (solución completa) | 116 tests, 0 fallos (+4 omitidos a propósito) |
| Arranque real de `DracPaste.exe` | Proceso vivo, escuchando en el puerto 47653, identidad generada y persistida |

**El bucle de eco, probado de punta a punta** (`CicloAntiEcoTest` en Kotlin y
`CicloAntiEcoTests` en C#): se simulan los dos dispositivos con su portapapeles y su
anti-eco, se conectan, y se comprueba que un clip da **una sola vuelta**. Cubre el caso
normal, copiar diez cosas seguidas, copiar lo mismo dos veces a mano, que los dos copien a
la vez, y que el ciclo se corte aunque el reloj no avance —una red local es lo bastante
rápida para que todo ocurra en el mismo milisegundo, así que la caducidad de la marca no
puede ser lo que rompa el bucle—.

### Una decisión que conviene conocer

**Los tests del vigilante del portapapeles están escritos pero desactivados**
(`VigilanteDelPortapapelesTests`, 4 tests con `Skip`). No hay forma de probar
`AddClipboardFormatListener` sin escribir en el portapapeles del sistema, que es uno solo y
compartido con lo que el usuario esté haciendo: ejecutarlos sin avisar le borraría lo que
tuviera copiado, y si era algo con formato no se podría restaurar.

Para ejecutarlos, con el portapapeles vacío, hay que quitar los `Skip` del fichero. La
lógica de rebote —que es la parte donde de verdad se cometen errores— sí está cubierta
siempre por `CicloAntiEcoTests`, que no toca nada del sistema.

### Pruebas manuales pendientes

**M2.1 · Copiar en el PC y pegar en el móvil**

1. Con el par emparejado y la notificación en «Conectado», copiar un texto en el PC.
2. Ir al móvil y pegar en cualquier app.

*Resultado esperado*: el texto está ahí, **en menos de un segundo** desde la copia. Repetir
con acentos, con un emoji y con un texto largo (varios párrafos).

**M2.2 · No hay bucle al copiar repetidamente**

1. Copiar diez textos distintos en el PC, uno detrás de otro, sin pausas largas.
2. Observar la notificación del móvil y el icono de la bandeja.

*Resultado esperado*: el móvil acaba con el último texto y nada se queda "parpadeando". En
el PC, el portapapeles no cambia solo después de la última copia.

*Ya cubierto automáticamente*: `CicloAntiEcoTests`. Esta prueba confirma que el
comportamiento se mantiene sobre el portapapeles real de Windows, que es donde vive el
formato propio.

**M2.3 · Un Ctrl+C en Word llega una sola vez**

1. Copiar una tabla o texto con formato desde Word o Excel.

*Resultado esperado*: el texto plano llega al móvil **una vez**, no tres o cuatro. Es lo
que comprueba el debounce de 100 ms.

**M2.4 · El servicio sobrevive en segundo plano**

1. Con el par conectado, apagar la pantalla del móvil y dejarlo **30 minutos**.
2. Sin tocar el móvil, copiar un texto en el PC.
3. Encender la pantalla y pegar.

*Resultado esperado*: el texto está en el portapapeles. La notificación seguía en
«Conectado» todo el rato. Este es el criterio que más probablemente falle en móviles
Xiaomi o Samsung sin la exención de batería, que llega en la Fase 5.

**M2.5 · El plan B de la escritura bloqueada**

Solo aplica si en el móvil concreto el sistema no deja escribir en segundo plano.

*Resultado esperado*: en vez de perderse el clip, la notificación cambia a «Clip recibido,
toca para pegarlo» con un botón «Pegar». Al pulsarlo, el texto queda en el portapapeles y
la notificación vuelve a su estado normal. En un Pixel esto no debería llegar a verse
nunca.

---

## Fase 3 · Android → Windows — CERRADA (2026-08-15)

### Qué se hizo

**La ventana invisible** (`ActividadCaptura`). Es la pieza que resuelve la asimetría de
Android: desde Android 10, una app solo puede leer el portapapeles con el foco de pantalla.
Cuando el usuario pulsa «Enviar portapapeles» en la notificación, se abre una activity
translúcida que no dibuja nada, no aparece en recientes y vive unos milisegundos: el tiempo
justo de tener el foco, leer y cerrarse.

**La lectura ocurre en `onWindowFocusChanged`, no en `onCreate`.** Es el riesgo número uno
de la tabla del plan y merece explicarse: cuando `onCreate` se ejecuta, la ventana todavía
no tiene el foco, así que el portapapeles devuelve `null` y el clip sale vacío. El fallo no
da ningún error —simplemente no se envía nada— y no se reproduce de forma fiable en el
emulador, así que es fácil darlo por bueno sin serlo. Además:

- Se lee **una sola vez** aunque el foco vaya y venga (una notificación que aparezca
  encima lo provoca), porque leer dos veces enviaría el clip dos veces.
- Si el foco no llega nunca, `onPause` cierra la activity: no puede quedarse abierta
  esperando algo que no va a pasar.

**El share target** (`ActividadCompartir`). La vía limpia: DracPaste aparece en el menú
«Compartir» de cualquier app, y el texto llega en el propio intent, así que **no pasa por
el portapapeles en ningún momento**. Lo que el usuario tuviera copiado se queda como
estaba.

**Los clips sensibles no viajan.** Cuando un gestor de contraseñas copia algo, marca el
clip; DracPaste lo respeta y avisa al usuario de por qué no se ha enviado, en lugar de
callar y parecer que falla.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:test` | 99 tests, 0 fallos |
| `gradlew :app:testDebugUnitTest` | 30 tests, 0 fallos |
| `gradlew :app:assembleDebug` | Correcto |
| Manifiesto compilado del APK (`aapt2 dump xmltree`) | Temas, `excludeFromRecents`, `singleTask`, share target y `foregroundServiceType=connectedDevice` correctos |

**La regla de los clips sensibles está fijada por escrito** (`MarcadoSensibleTest`). Se
extrajo a una función pura precisamente para poder probarla: equivocarse en el nombre de
una clave no daría ningún error visible —la contraseña viajaría igual— y no hay forma de
notarlo mirando la app.

Se comprueban las dos cadenas que usan los gestores: la del sistema
(`android.content.extra.IS_SENSITIVE`) y la de AndroidX. La constante del SDK solo existe
desde Android 13 y el `minSdk` es 29, así que se usa el valor literal para que la regla
valga también en Android 10, 11 y 12 — que es donde el sistema no la conoce y nadie más la
va a mirar.

**El manifiesto tiene sus propios tests** (`ManifiestoTest`). Puede parecer excesivo, pero
varias decisiones del plan solo existen ahí: si alguien quita `excludeFromRecents`, el
usuario empieza a ver una ventana fantasma en la lista de apps cada vez que envía un clip;
si el tema translúcido se cambia por el normal, ve un parpadeo blanco a pantalla completa.
Ninguna de las dos cosas rompe nada ni da un error, así que nadie se entera hasta probarlo
a mano.

Se comprueba también que **no se piden permisos de más** (`READ_LOGS`, almacenamiento,
contactos, ubicación…): en una app cuyo argumento es la privacidad, cada permiso del
manifiesto es algo que el usuario ve y tiene que creerse.

### Una corrección sobre la marcha

Un clip demasiado grande acababa mostrando «Sin conexión», porque el error de tamaño se
mezclaba con el de red en el mismo `catch`. Ahora el tamaño se comprueba antes y el aviso
dice lo que pasa de verdad: mandar al usuario a mirar el WiFi por un texto de 300 KB es
hacerle perder el tiempo.

### Pruebas manuales pendientes

**M3.1 · El botón de la notificación envía lo copiado** *(prueba crítica)*

1. Copiar un texto en **cualquier app** del móvil (el navegador, unas notas, WhatsApp).
2. Bajar la barra de notificaciones y pulsar «Enviar portapapeles» en la de DracPaste.
3. Ir al PC y pegar.

*Resultado esperado*: el texto está ahí, **en menos de un segundo**. No debe verse ningún
parpadeo ni ninguna ventana al pulsar el botón, y DracPaste **no** debe aparecer en la
lista de apps recientes.

**Esta es la prueba que no se puede saltar.** Es el riesgo número uno del plan: si la
lectura se hiciera en `onCreate`, aquí llegaría un clip vacío o no llegaría nada. Conviene
repetirla desde tres apps distintas y con la pantalla recién desbloqueada.

**M3.2 · Compartir sin tocar el portapapeles**

1. Copiar en el móvil un texto **A** cualquiera.
2. En otra app, seleccionar un texto **B** y usar «Compartir» → «Enviar al PC».
3. Pegar en el PC. Después, pegar en el propio móvil.

*Resultado esperado*: en el PC aparece **B**; en el móvil sigue estando **A**. La vía de
compartir no toca el portapapeles.

**M3.3 · Una contraseña no sale del móvil** *(prueba crítica)*

1. Abrir un gestor de contraseñas (Bitwarden, 1Password, el de Google) y copiar una
   contraseña con su botón de copiar.
2. Pulsar «Enviar portapapeles» en la notificación de DracPaste.

*Resultado esperado*: el móvil avisa de que «ese contenido está marcado como sensible y no
se comparte», y en el PC **no aparece nada**. El portapapeles del PC conserva lo que
tuviera.

*Ya cubierto parcialmente*: la regla tiene tests. Lo que hay que confirmar a mano es que el
gestor concreto que usa el usuario marca sus clips, porque no todos lo hacen.

**M3.4 · No hay bucle en la dirección móvil → PC**

1. Enviar un clip desde el móvil con el botón.
2. Observar el móvil unos segundos.

*Resultado esperado*: el PC recibe el texto y **no lo devuelve**. La notificación del móvil
no entra en un ciclo de «Enviado / Recibido».

**M3.5 · Sin conexión, el clip no se guarda para luego**

1. Poner el móvil en modo avión.
2. Pulsar «Enviar portapapeles».
3. Quitar el modo avión y esperar a que reconecte.

*Resultado esperado*: el móvil dice «Sin conexión con [PC]». Al reconectar, ese texto **no**
aparece de pronto en el PC. Es la decisión del plan §4.3: sin cola de clips, porque algo
copiado hace rato apareciendo cuando el usuario ya no se acuerda es peor que no enviarlo.

---

## Fase 4 · Emparejamiento pulido y multi-PC — CERRADA (2026-08-15)

### Qué se hizo

**El QR de verdad en Windows.** La ventana muestra un QR grande generado con QRCoder, la
huella, una cuenta atrás de los dos minutos que dura el código y —debajo— el texto por si
la cámara falla. El JSON lleva una clave pública de 32 bytes y un token de 16: copiarlo a
mano es tedioso y fácil de estropear, pero una cámara sucia o una pantalla con poco brillo
no pueden dejar al usuario sin poder emparejar, así que las dos vías conviven.

Al cerrar la ventana, el token se revoca aunque no haya caducado: quien ha dejado de mirar
la pantalla no espera que su código siga sirviendo.

**El escáner en Android**, con CameraX y ML Kit. Se usa el modelo de códigos de barras
**embebido en el APK**, no el de Google Play Services: emparejar no puede depender de
descargar nada en ese momento ni de que Play Services esté instalado. Un QR se lee varias
veces por segundo mientras esté delante, así que hay un cierre para que solo la primera
cuente — si no, el emparejamiento se intentaría diez veces con un token que solo vale una
y el usuario vería una ristra de errores.

**Desemparejar, por los dos lados.** Desde el PC (ventana de ajustes con la lista, la
huella y la fecha) y desde el móvil. En ambos casos se avisa al otro con un `UNPAIR`
**antes** de borrar la clave, porque después ya no habría forma de cifrarle nada. Si el
otro no está disponible, el olvido local sigue adelante igual: el usuario ha dicho que ya
no quiere ese dispositivo, y que la app dependiera de alcanzarlo para obedecerle sería
absurdo.

**Multi-PC en el móvil**: lista de PCs emparejados con selector de destino activo y relevo
automático si se desempareja el que estaba activo.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:test` + `:app:testDebugUnitTest` | 129 tests, 0 fallos |
| `dotnet test` (solución completa) | 123 tests, 0 fallos (+4 omitidos a propósito) |
| **`scripts/prueba-cruzada.ps1`** | **Superada** |
| Capturas de las ventanas de Windows | Revisadas y corregidas |
| App instalada y arrancada en el emulador Android 14 | Pantalla revisada |

### La prueba cruzada

`scripts/prueba-cruzada.ps1` arranca el **servidor real de Windows** —el mismo
`ServidorDracPaste` que usa la app de bandeja— y lanza contra él un cliente **Kotlin** que
se empareja, abre sesión y cruza un clip en cada dirección.

Es la única prueba que demuestra que las dos implementaciones se entienden **de verdad**.
Los vectores de `docs/protocol.md` §7 verifican que Bouncy Castle y libsodium producen los
mismos bytes, pero eso no garantiza que el diálogo completo funcione entre dos lenguajes:
un campo JSON mal nombrado, un contador de nonce desalineado o un `flush` que falta no
aparecen en ningún vector.

El texto de prueba lleva acentos y un emoji (`desde-kotlin-àéî-🐉`) y se comprueba que
llega **byte a byte**, incluido su `origin_id`: si ese hash no coincidiera entre los dos
lados, el anti-eco no reconocería los clips del otro y rebotarían indefinidamente.

Resultado: emparejamiento correcto, misma huella en los dos extremos, y los dos textos
cruzados sin alteración.

### La interfaz, mirada además de compilada

Las ventanas de Windows se dibujan a PNG en los tests (`CapturaDeVentanasTests`) y se han
revisado. Que una ventana compile no dice nada de cómo se ve, y mirarlas destapó tres
defectos que ningún test habría cogido:

1. La línea «Este PC: … · IP:puerto» quedaba **cortada por la mitad**: el bloque de texto
   tenía cuatro líneas y solo 72 px de alto.
2. La ventana enseñaba «Huella: se mostrará al terminar» en la tipografía monoespaciada
   grande reservada para la huella real, como si eso fuera una huella. Ahora, cuando
   todavía no se conoce —depende de la clave pública del móvil, que llega al emparejar—,
   se explica con otro texto y otra tipografía.
3. El texto alternativo aparecía **resaltado en azul** al abrirse, como si el usuario ya lo
   hubiera seleccionado.

En la ventana de ajustes, las columnas sumaban más que el ancho disponible y salía una
barra de desplazamiento horizontal con la última columna cortada.

En Android, la app se instaló en el emulador (Android 14) y se revisó la pantalla
principal. La estructura y los textos están bien; el tema sigue siendo el morado por
defecto de Material 3, no la identidad verde y oro de la familia Drac. Queda anotado para
la Fase 6.

### Pruebas manuales pendientes

**M4.1 · Emparejar escaneando, en menos de 30 segundos**

1. En el PC: bandeja → «Emparejar un móvil…».
2. En el móvil: abrir DracPaste → «Escanear el código del PC» → conceder la cámara.
3. Apuntar al QR.

*Resultado esperado*: el emparejamiento se completa **sin teclear nada**. El móvil muestra
una huella tipo `A3F2-9C71` y el PC muestra un globo con **la misma**. Repetir con poca luz
y con la pantalla del PC a brillo bajo.

**M4.2 · El código caduca a los dos minutos**

1. Abrir la ventana de emparejamiento y dejarla pasar de la cuenta atrás.

*Resultado esperado*: la cuenta atrás llega a cero y la ventana avisa de que hay que
cerrarla y volver a abrirla. Escanear ese QR caducado falla.

**M4.3 · Desemparejar impide reconectar** *(prueba crítica)*

1. Con el par conectado, en el PC: Ajustes → seleccionar el móvil → «Desemparejar».
2. Confirmar.

*Resultado esperado*: el móvil pasa a «Sin emparejar» **solo**, sin tocarlo. Copiar algo en
el PC ya no llega. Reiniciar el móvil no lo arregla: hace falta emparejar de nuevo.

Repetir la prueba en el otro sentido (desemparejar desde el móvil) y también **con el PC
apagado**, para confirmar que el móvil lo olvida igual.

**M4.4 · Con dos PCs, los clips van solo al activo**

Necesita un segundo PC (o el mismo con otro usuario de Windows).

1. Emparejar el móvil con dos PCs.
2. En el móvil, comprobar que solo uno está marcado como «Destino activo».
3. Copiar algo en el PC **no** activo.
4. Cambiar el destino activo en el móvil y volver a copiar en cada uno.

*Resultado esperado*: solo llega lo que se copia en el PC activo. Cambiar de activo surte
efecto **sin reiniciar** ni la app ni el móvil.

**M4.5 · Emparejar pegando el texto sigue funcionando**

1. En el PC, pulsar «Copiar el texto» en la ventana de emparejamiento.
2. Pasarlo al móvil y usar «La cámara no funciona: pegar el texto».

*Resultado esperado*: empareja igual. Es la salida cuando la cámara está rota, sucia o el
usuario le ha denegado el permiso.

---

## Fase 5 · Robustez y ajustes — CERRADA (2026-08-15)

### Qué se hizo

**Los tres avisos que despiertan la reconexión** (`VigilanteDeRed`). Sin ellos, el móvil
solo reintentaría siguiendo el backoff, que llega a esperar 30 segundos:

- **Cambio de red** (`NetworkCallback`): al pasar del WiFi a datos o al revés, el socket
  anterior está muerto aunque no lo parezca, y la IP recordada pertenece a otra red. Se
  reacciona a `onAvailable` pero **no** a `onLost`: un salto entre puntos de acceso levanta
  y tira redes en un instante, y cortar la sesión en cada parpadeo daría más problemas de
  los que resuelve. Quien decide que la conexión ha muerto es el PING.
- **Pantalla encendida** (`ACTION_SCREEN_ON`): es cuando el usuario va a usar el móvil, y
  mitiga Doze sin pedir un `WakeLock` ni mantener nada despierto.
- **Arranque** (`BOOT_COMPLETED`): el servicio vuelve solo tras reiniciar, pero **solo si
  hay algún PC emparejado**: una notificación permanente en un móvil donde la app aún no
  está configurada sería molesta y no serviría de nada.

**La batería, explicada en vez de exigida.** La pantalla de ajustes dice qué pasa si
Android restringe la app y por qué el consumo real es mínimo —no hace nada mientras no
copies—, y ofrece el diálogo del sistema. Se usa el intent que **pregunta** al usuario, no
el que concede la exención en silencio: quitarle a alguien una protección de batería sin
decírselo no es aceptable en una app que le pide confianza.

**Guía por fabricante** (`GuiaDeFabricante`). La exención estándar no basta en Xiaomi,
Samsung, Huawei, OPPO y OnePlus: cada uno tiene su propia capa que mata procesos con sus
reglas, y el ajuste está en un sitio distinto. Un mensaje genérico del tipo «desactiva la
optimización de batería» no le sirve de nada a quien tiene un Xiaomi, donde lo que importa
se llama «Sin restricciones» y está en otro menú. La app detecta la marca, enseña los pasos
concretos y ofrece abrir esa pantalla —comprobando antes que existe, porque cambian de
nombre entre versiones y un intent a ciegas cerraría la app.

**Ajustes completos en los dos lados**: pausar la sincronización (que no tira la conexión,
para que el usuario distinga «lo he pausado yo» de «se ha roto algo»), aviso al recibir, y
en Windows además arranque con el inicio de sesión mediante la clave `Run` del usuario —no
la de la máquina: no necesita administrador y cada usuario decide por su cuenta—.

Dos ajustes se muestran como **valores, no como interruptores**: «solo red local» y
«los clips sensibles nunca se sincronizan». No es un descuido: en v1 no hay relay que
activar, y una app cuyo argumento es la privacidad no debería ofrecer un botón para mandar
contraseñas por la red aunque alguien lo pidiera. Enseñarlos igualmente sirve para que el
usuario sepa qué hace la app.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| `gradlew :protocolo:test` + `:app:testDebugUnitTest` | 129 tests, 0 fallos |
| `dotnet test` (solución completa), 4 vueltas seguidas | 125 tests, 0 fallos, sin intermitencias |
| `gradlew :app:assembleDebug` | Correcto |
| Pantalla de ajustes en el emulador Android 14 | Revisada |
| La misma pantalla con `font_scale 1.5` | Revisada, nada se corta |

### Un fallo real encontrado

**Cerrar DracPaste terminaba con una excepción.** Al parar el servidor, el
`AcceptTcpClientAsync` que estaba esperando lanza `ObjectDisposedException` —no
`OperationCanceledException`, que era lo único que se capturaba—, y esa excepción se
propagaba desde `DisposeAsync` hasta el cierre de la aplicación.

Lo destapó un test que falló de forma intermitente: depende de si el listener se para antes
o después de que el bucle vuelva a entrar en `Accept`. Ahora ese caso se trata como el
final normal que es, y `DisposeAsync` no deja escapar nada: cerrar no puede fallar.

### Pruebas manuales pendientes

Estas son las que el plan marca como **medibles** y ninguna se puede automatizar.

**M5.1 · Suspender el PC y despertarlo → reconexión en menos de 5 s**

1. Con el par conectado, suspender el PC (no apagarlo).
2. Esperar un minuto y despertarlo.
3. Cronometrar desde que la pantalla del PC vuelve hasta que el móvil dice «Conectado».

*Resultado esperado*: menos de 5 segundos, sin tocar nada. Es lo que debe conseguir el
reanuncio de mDNS al despertar (`PowerModeChanged`).

**M5.2 · Cambiar de WiFi y volver → reconexión en menos de 5 s**

1. Con el par conectado, cambiar el móvil a otra red WiFi (o a datos).
2. Volver a la red del PC.

*Resultado esperado*: menos de 5 segundos desde que vuelve a la red buena. Sin el
`NetworkCallback`, esto tardaría hasta 30 segundos por el backoff.

**M5.3 · WiFi apagado 10 minutos → reconexión en menos de 10 s**

1. Apagar el WiFi del móvil y dejarlo **10 minutos** con la pantalla apagada.
2. Encender el WiFi.

*Resultado esperado*: menos de 10 segundos. Este es el caso donde Doze ya ha entrado y el
backoff está en su tope de 30 s: lo que tiene que salvarlo es el aviso de red o el de
pantalla encendida.

**M5.4 · Reiniciar el móvil → el servicio vuelve solo**

1. Reiniciar el móvil por completo.
2. **No abrir DracPaste.**
3. Copiar algo en el PC y mirar el móvil.

*Resultado esperado*: la notificación de DracPaste está ahí sin haber abierto la app, y el
texto llega. Si falla, casi seguro es la restricción de arranque automático del fabricante:
mirar la guía de la propia app.

**M5.5 · Matar el proceso → el servicio se restaura**

1. Ajustes → Aplicaciones → DracPaste → «Forzar detención».
2. Esperar un par de minutos sin tocar el móvil.

*Resultado esperado*: el servicio vuelve por `START_STICKY`. En algunos fabricantes, forzar
la detención lo deja muerto hasta que el usuario abra la app: eso es comportamiento del
sistema y conviene anotar en qué móvil pasa.

**M5.6 · La exención de batería y la guía del fabricante**

1. Ajustes de DracPaste → «Quitar las restricciones».
2. Si el móvil es Xiaomi, Samsung, Huawei, OPPO o OnePlus: «Ver qué hay que tocar» y
   «Abrir esos ajustes».

*Resultado esperado*: el diálogo del sistema aparece y, al conceder, la app pasa a
«Batería: sin restricciones ✓» sin reiniciarla. El botón de abrir los ajustes del
fabricante **no puede cerrar la app** aunque esa pantalla no exista en esa versión.

**M5.7 · Pausar de verdad pausa**

1. Activar «Pausar la sincronización» en el móvil.
2. Copiar algo en el PC. Copiar algo en el móvil y pulsar «Enviar portapapeles».
3. Desactivar la pausa y repetir.

*Resultado esperado*: con la pausa activa no viaja nada **en ninguna de las dos
direcciones**, pero la notificación sigue diciendo que está conectado con el PC —eso es lo
que distingue una pausa de una avería—. Al desactivarla, todo vuelve a funcionar sin
reiniciar nada.

**M5.8 · El servicio aguanta la noche** *(la prueba larga)*

1. Dejar el móvil cargando toda la noche, sin tocarlo.
2. Por la mañana, copiar algo en el PC.

*Resultado esperado*: llega. Si no llega pero sí lo hace al desbloquear el móvil, el
sistema mató el servicio y la exención de batería no bastó: apuntar el fabricante y la
versión de Android.

---

## Fase 6 · Empaquetado — CERRADA (2026-08-15)

### Qué se hizo

**Instalador de Windows** (`windows/instalador/DracPaste.iss`, Inno Setup 6). Generado y
verificado: `dist/DracPaste-0.1.0-instalador.exe`, **49,3 MB**.

- Publicación **autocontenida**: trae su propio runtime, así que el usuario no tiene que
  instalar .NET antes. Es la primera piedra con la que tropieza quien solo quiere una app.
- **Instalación por usuario, sin pedir administrador**: la identidad se cifra con DPAPI del
  usuario actual, así que una instalación para toda la máquina no aportaría nada y solo
  añadiría una petición de permisos que asusta.
- Casilla de arranque con el inicio de sesión.
- Al desinstalar, **pregunta** si borrar las claves en vez de hacerlo por su cuenta: quien
  reinstala no debería perder los emparejamientos de todos sus móviles por sorpresa.

Se eligió Inno Setup y no MSIX porque MSIX exige firmar con un certificado de confianza:
sin él, Windows rechaza el paquete y no hay forma de instalarlo. Un instalador clásico sin
firmar solo enseña el aviso de SmartScreen, que el usuario puede saltarse. Como esto se
distribuye fuera de la Store, es la única vía practicable.

**Firma del APK** (`scripts/publicar-android.ps1`). El script crea la keystore la primera
vez —preguntando la contraseña—, compila el APK de publicación, y **comprueba que va
firmado con la clave correcta**: un `keystore.properties` mal leído produce un APK firmado
con la clave de depuración, y eso no se nota hasta que falla la instalación en el móvil.

**La keystore no se ha creado.** Es lo único que queda pendiente de la fase y es
deliberado: una clave de firma es del usuario y su contraseña la tiene que elegir él. Ver
abajo.

**Documentación de usuario final**: `docs/guia-de-uso.md`, escrita para alguien que no ha
leído el plan. Explica la asimetría de Android como lo que es —una limitación del sistema,
no un defecto de la app— y dedica una sección a qué hace y qué **no** hace con los datos.

### Verificado automáticamente

| Comprobación | Resultado |
|---|---|
| Instalador generado con Inno Setup | `DracPaste-0.1.0-instalador.exe`, 49,3 MB |
| `dotnet publish` autocontenido | 162 MB en `dist/publicado`, ejecuta |
| `gradlew :app:assembleRelease` | APK de 25,5 MB con R8 |
| APK de publicación instalado y arrancado en el emulador | Sin errores en logcat |
| Serializadores del protocolo tras R8 (`apkanalyzer`) | Conservados con su nombre |
| Tests, ambos lados | 129 Android + 125 Windows, 0 fallos |

### Dos cosas que aparecieron al empaquetar

**1. Medio megabyte de código muerto en el APK.** Las reglas de R8 que venían del esqueleto
incluían un `-keep class org.bouncycastle.jcajce.provider.**` "por si acaso". Al mirar el
DEX con `apkanalyzer` resultó que conservaba **4.050 clases** del proveedor JCE de Bouncy
Castle que la app no toca: DracPaste usa la API de bajo nivel y la llama directamente,
precisamente para no depender de reflexión (`docs/decisions.md` D-003).

Quitada esa regla, el APK baja de 27,5 MB a **25,5 MB**. Las reglas que sí importan —las de
kotlinx.serialization, que genera los serializadores por nombre de clase— se han verificado
en el DEX del APK real: siguen ahí con su nombre completo.

**2. Los tests instrumentados no pueden correr sobre el build de publicación.** Se intentó
(`testBuildType = "release"`) para comprobar de paso que R8 no rompía el cifrado, pero al
minificar también el APK de test, el runner de AndroidX se queda sin métodos que busca por
reflexión y la instrumentación no llega ni a arrancar. Se ha vuelto a dejar en depuración,
donde su valor real es otro: probar el **Android Keystore de verdad**, que no existe en el
JVM.

### Pruebas manuales pendientes

**M6.1 · Crear la keystore de firma** *(hay que hacerlo antes de distribuir nada)*

```powershell
powershell -ExecutionPolicy Bypass -File scripts\publicar-android.ps1
```

Pedirá una contraseña y creará `%USERPROFILE%\dracpaste-release.jks`.

> **Guarda esa keystore y su contraseña donde no se pierdan.** Sin ellas no se puede
> publicar ninguna actualización que Android acepte encima de la versión ya instalada: la
> única salida sería desinstalar y volver a empezar, perdiendo los emparejamientos.

No se ha creado de forma automática a propósito: la contraseña de una clave de firma la
elige su dueño.

**M6.2 · El APK de publicación empareja de verdad** *(prueba crítica)*

Con el APK firmado ya instalado en un móvil real, hacer el emparejamiento completo (M4.1) y
enviar un clip en cada dirección.

**Por qué esta prueba no se puede saltar**: es la única que demuestra que R8 no ha roto el
cifrado ni la serialización. Un fallo aquí sería de los más caros posibles —una app que
instala bien, arranca bien y no empareja nunca, sin ningún error que explique por qué— y
**solo se manifiesta en la versión que se publica**, nunca en la de depuración.

Se intentó automatizar sobre el emulador, pero introducir el JSON del emparejamiento por
`adb shell input text` no es viable: entre PowerShell, adb y el shell de Android, las
comillas dobles del JSON se pierden por el camino. Lo que sí quedó comprobado
automáticamente es que el APK minificado instala, arranca sin errores y conserva los
serializadores.

**M6.3 · Instalación limpia siguiendo solo la documentación**

En un PC y un móvil donde DracPaste no haya estado nunca, seguir `docs/guia-de-uso.md` de
principio a fin **sin consultar nada más**, y anotar cualquier punto donde haga falta
adivinar algo. Es el criterio de aceptación de la fase.

**M6.4 · Desinstalar y volver a instalar conserva los emparejamientos**

1. Desinstalar DracPaste del PC y responder **«No»** a la pregunta de borrar las claves.
2. Volver a instalarlo.

*Resultado esperado*: el móvil se reconecta solo, sin volver a emparejar.

Repetir respondiendo **«Sí»**: entonces el móvil debe quedarse en «Sin emparejar» y hacer
falta un código nuevo.

---

# Pruebas con hardware real — 15-ago-2026

Hechas con un Pixel 8 Pro (Android 17) y la app de bandeja **real** en Windows, con el APK
**de publicación** (minificado con R8 y firmado con la clave de producción).

## Superadas

| Prueba | Resultado |
|---|---|
| **M6.2 · El APK de publicación empareja** | **Sí.** El Pixel 8 Pro emparejado con la app real: «MARC» como destino activo, huella `96C4-3AC4` **idéntica en los dos lados**. R8 no rompe el cifrado ni la serialización. |
| **M2.1 · PC → móvil** | Se copia `del-PC-…-àéñ-🐉` en el PC y llega **byte a byte** al portapapeles del Pixel, acentos y emoji incluidos. |
| **M3.1 · El botón de la notificación** *(la crítica)* | **Sí.** Al pulsar «Enviar portapapeles», la notificación pasa a «Enviado a MARC»: la ventana invisible leyó el portapapeles **con el foco puesto** y el envío salió. Es el riesgo número uno del plan, confirmado en hardware real. |
| **M3.2 · Compartir** | El share target envía el texto al PC sin tocar el portapapeles del móvil. |
| **Anti-eco** | Apareció solo, y funcionando: un texto que acababa de llegar del PC **no se reenvió** al capturarlo. Lo descubrí porque una prueba mal diseñada mía se lo comió. |
| **Reconexión tras reiniciar el PC** | Se mata DracPaste en el PC y se vuelve a abrir: el móvil vuelve a «Conectado con MARC» **solo**, sin tocarlo. |
| **M4.2 · Caducidad del token** | La ventana avisa a los 2 minutos, y el servidor **rechaza el token caducado cerrando la conexión sin contestar**, como manda §3.2. |
| `excludeFromRecents` | Las tareas de DracPaste salen en `mHiddenTasks`: la ventana invisible no deja rastro en la lista de apps. |
| Activities internas protegidas | `ActividadCaptura` y `ActividadPegar` son `exported="false"` de verdad: ni siquiera adb puede lanzarlas. Nadie de fuera puede disparar una lectura del portapapeles. |
| Actualización 1.0 → 1.1 → 1.2 en el móvil | Se instala **encima, sin desinstalar**: la firma es la correcta y las actualizaciones futuras funcionarán. |
| Instancia única en Windows | Al lanzar una segunda copia, se cierra sola sin molestar. |

Parte de las pruebas se hicieron además con el **cliente Kotlin**
(`:protocolo:clienteDePrueba`), que ejecuta el mismo código de protocolo que el móvil. Eso
permitió repetirlas sin depender de que el cable estuviera puesto.

## El fallo que apareció, y no era pequeño

**El Firewall de Windows impedía emparejar en cualquier PC.** Se arregló en dos tandas,
porque la primera no bastaba:

- **v1.1** — no había ninguna regla. El ping funcionaba y la conexión TCP moría en un
  timeout, así que el móvil decía «¿estáis en la misma red?» y mandaba al usuario a
  revisar el WiFi, que estaba perfecto. Se añadieron reglas en el instalador, detección en
  la app con un botón para crearlas, y un mensaje honesto en el móvil.
- **v1.2** — las reglas de la 1.1 valían solo para redes «privada» y «dominio», que parecía
  lo prudente. **No servía**: Windows clasifica como *pública* cualquier red en la que el
  usuario dijo que no quería que el equipo fuese detectable, y eso incluye la mayoría de
  redes domésticas —la de este PC, sin ir más lejos—. La regla existía, estaba habilitada y
  no aplicaba, con un síntoma idéntico al de no tener ninguna.

## Emparejar con el QR, y el bug que destapó — 16-ago-2026

El usuario emparejó **escaneando el QR con la cámara** (M4.1) y desvinculó y volvió a
vincular varias veces. El QR funciona.

Pero apareció un fallo que solo se ve haciéndolo así:

**Al emparejar con la app ya abierta, no pasaba nada.** El PC registraba el móvil, pero la
notificación seguía diciendo «Sin emparejar» y no se establecía ninguna conexión. Había que
cerrar y reabrir la app para que arrancara.

La causa: tras emparejar, la pantalla llamaba a `ServicioDracPaste.arrancar(contexto)` **sin
acción**. Si el servicio ya estaba en marcha —lo normal, porque se arranca al abrir la
app—, `onStartCommand` no tenía nada que hacer y se quedaba como estaba, sin releer el
emparejamiento nuevo. Ahora se arranca con `ACCION_RELEER_EMPAREJAMIENTO`.

**Por qué no lo cogieron las pruebas automáticas**: todas hacían `am force-stop` antes de
emparejar, así que el servicio siempre arrancaba de cero y leía el emparejamiento en
`onCreate`. El camino que sigue una persona de verdad —abrir la app y emparejar— era justo
el que no se probaba.

De la misma sesión salió una mejora de la notificación: decía «Lo que copies en el PC
llegará aquí», lo que hace pensar que la otra dirección también es automática. El usuario
copió algo en el móvil y se quedó esperando. Ahora dice «Lo del PC llega solo · para enviar
lo tuyo, despliega y toca Enviar», con `BigTextStyle` para que no se corte.

También quedó comprobado, con gestos reales:

- **Móvil → PC con el botón de la notificación**: dos textos enviados y recibidos.
- **Desvincular**: el PC y el móvil se enteran los dos.
- **Reconexión automática**: «Reconectando» → «Conectado» sola, varias veces.

## Sospecha descartada: el PC sí suelta la conexión (17-ago-2026)

**Resuelta el mismo día: era ruido de mis pruebas, no un fallo.** Se deja escrita porque
el síntoma asusta y es fácil volver a tropezar con él.

`ServidorDracPaste.AtenderSesionAsync` ya hace lo correcto cuando llega una conexión nueva
teniendo una sesión viva:

```csharp
// Si ya había una sesión, esta la sustituye: el móvil ha reconectado y la
// anterior es un socket zombi que nadie va a cerrar desde el otro lado.
_sesion?.Cerrar();
```

Y el `finally` solo limpia el estado si la sesión que termina sigue siendo la actual
(`ReferenceEquals`), así que el relevo no se pisa a sí mismo.

Los `EOFException` del móvil venían de que yo estaba **cerrando y relanzando el ejecutable
del PC** entre prueba y prueba: el móvil reintentaba contra un servidor que se estaba
apagando. Ninguna persona hace eso; yo lo hice diez veces en una hora.

La lección: un log de errores recogido mientras uno reinicia cosas no prueba nada. Antes de
declarar un fallo hay que reproducirlo sin tocar nada más.

## La sospecha, tal como se registró

Probando el envío móvil → PC el 17-ago-2026, todos los intentos fallaron con esto en el
móvil:

```
D/DracPaste.Cliente: Conexión fallida contra 192.168.1.140:47653
D/DracPaste.Cliente: java.io.EOFException: La conexión se cerró tras 0 de 4 bytes
```

Y a la vez, en el PC:

```
TCP  192.168.1.140:47653  192.168.1.146:35994  ESTABLISHED  8904
```

Es decir: el servidor **acepta la conexión nueva y la cierra sin decir nada**, mientras
mantiene viva una anterior. La hipótesis es que un `am force-stop` del móvil —o cualquier
corte brusco: apagar el WiFi, quedarse sin batería— deja el socket a medio cerrar en el
PC, que sigue creyendo que tiene un móvil conectado y rechaza al que vuelve.

Si se confirma, importa: no es un caso de laboratorio, es lo que pasa cuando el móvil se
queda sin cobertura. Habría que comprobar qué hace el servidor cuando llega una conexión
nueva de un dispositivo que ya tiene sesión: lo razonable es que la nueva sustituya a la
vieja, porque la vieja ya no puede estar viva.

Queda como sospecha y no como fallo confirmado porque llegó entre pruebas en las que yo
había hecho varios `force-stop` seguidos, que no es el uso normal de nadie.

## El rediseño — 17-ago-2026

Claude Design entregó identidad y pantallas (todo en `design-handoff/`). Se implementó en
tres tandas: los cambios de comportamiento, la paleta, y los iconos.

**Comportamiento.** El botón «Enviar lo copiado al PC» en la pantalla principal es la pieza
que resuelve la asimetría: con la app en primer plano **sí hay foco**, así que leer el
portapapeles es legal y no hace falta la activity invisible. Además: los insets del sistema
(el título pisaba el reloj), la confirmación de huella con «No coinciden» que desempareja de
verdad, el código de emparejamiento que se renueva solo al caducar, el aviso del firewall
como panel dentro de la ventana, y el icono de bandeja distinto por estado.

**Paleta.** Papel y tinta con terracota, en los dos lados. El terracota solo marca acciones,
nunca estado, porque el icono de bandeja es monocromo y allí el color no existe. Sin color
dinámico dentro de la app: la pantalla de la huella tiene que verse igual en el móvil, en el
PC y en una captura que alguien mande pidiendo ayuda.

**Iconos.** El logo entero en el lanzador y de 48 px en adelante en el `.ico`; la cabeza sola
en bandeja y notificación, que es lo único que sobrevive a 16 px.

### Lo que costó, por si vuelve a pasar

- **El `.ico` con PNG dentro se ve como ruido de colores.** El formato los admite desde
  Vista, pero GDI+ los interpreta como DIB igualmente. Van como DIB… salvo el de 256, que
  **tiene** que ir en PNG: un DIB de ese tamaño ocupa 256 KB y hace que algunos cargadores
  descarten el fichero entero.
- **`GetHicon()` + `Icon.FromHandle` + `Clone()` no sirve para la bandeja.** El clon apunta
  al mismo handle, así que al destruirlo la bandeja pinta el icono de error. Y aunque se
  arregle eso, ese camino pierde el canal alfa al pasar por el guardado del sistema: el
  dragón desaparecía y quedaban solo las marcas de estado flotando.
- **En barra de tareas oscura una silueta negra es invisible**, y Windows 11 la pone oscura
  de fábrica. Se lee el tema del registro y se pinta en crema o en tinta.
- **Añadir bloques a una ventana de altura fija deja sin sitio al que rellena el hueco.** El
  aviso de firewall y la barra de caducidad dejaron la caja del texto alternativo en cero
  píxeles, con los botones montados sobre su etiqueta. Compilaba y el test de tamaño
  pasaba; ahora el test comprueba también lo que le queda al relleno.

## Todavía sin probar

Necesitan tiempo, o gestos que no se pueden automatizar:

- **M3.3 · que una contraseña no salga del móvil.** La regla tiene tests, pero falta ver
  que el gestor concreto que use marca sus clips. No se puede simular: escribir un clip
  con `EXTRA_IS_SENSITIVE` requiere ser otra app.
- **M4.1 · emparejar escaneando el QR con la cámara.** Aquí se emparejó pegando el código;
  el escáner sigue sin probarse con una cámara real apuntando a una pantalla.
- **M1.4, M5.1–M5.3 · reconexiones** tras cortar el WiFi del móvil, cambiar de red y
  suspender el PC. (La reconexión al reiniciar el PC sí está probada.)
- **M5.4, M5.5 · reiniciar el móvil** y matar el proceso.
- **M2.4, M5.8 · aguante en segundo plano**: 30 minutos y una noche entera. Son las que
  más dicen sobre si la app es usable de verdad, y las únicas que no se pueden acelerar.

---

# Resumen final

## Estado global

**Las siete fases del plan están implementadas y cerradas.** DracPaste funciona en los dos
sentidos, cifrado de extremo a extremo, sin nube ni cuentas, con instalador para Windows y
APK para Android.

| | Estado |
|---|---|
| Fases 0–6 | Cerradas |
| Tests automáticos | **254 en verde** (129 Android, 125 Windows), 0 fallos |
| Prueba cruzada Kotlin ↔ C# por socket real | Superada |
| Instalador de Windows | Generado, 49,3 MB |
| APK de publicación | Generado, 25,5 MB — **falta firmarlo con la keystore definitiva** |
| Documentación de usuario | `docs/guia-de-uso.md` |

**Lo único que impide distribuir hoy mismo** es la keystore de firma de Android (M6.1), que
no se ha creado a propósito porque su contraseña la tiene que elegir el usuario.

## Lo que se probó y lo que no

Todo lo que no depende de hardware está cubierto por tests que corren en segundos: el
protocolo entero, el cifrado con vectores del RFC, el framing, el anti-replay, el bucle de
eco simulado con los dos dispositivos, la máquina de estados con sus 16 transiciones, el
handshake y el emparejamiento sobre sockets de loopback, el servidor real atendido por un
cliente que hace lo mismo que hará el móvil, y la interoperabilidad entre las dos
implementaciones.

Lo que **no** se ha podido probar es lo que exige un móvil físico, una red WiFi de verdad y
tiempo: reconexiones medidas en segundos, supervivencia del servicio durante la noche,
comportamiento de cada fabricante y latencia percibida. Todo eso está listado arriba, fase
por fase, con pasos exactos.

## Riesgos detectados durante la implementación

**1. El APK de publicación no se ha probado emparejando.** Es el riesgo mayor que queda.
R8 puede romper la serialización o el cifrado de formas que no dan ningún error visible, y
solo ocurren en la versión que se publica. Se ha mitigado verificando en el DEX que los
serializadores sobreviven y que el APK arranca, pero **no sustituye a M6.2**.

**2. Los fabricantes que matan servicios.** El plan ya lo anticipaba y la app trae guía por
marca, pero es una carrera que no se gana del todo: cada versión de MIUI o One UI cambia
dónde está el ajuste. Si el usuario final es un Pixel, el riesgo es bajo.

**3. mDNS en redes hostiles.** Si el router aísla clientes, DracPaste no puede funcionar y
no hay nada que programar contra eso. La app lo dice, pero el usuario podría no entender por
qué su móvil "no ve" el PC. La conexión manual por IP prevista en el plan §7 **no está
implementada**: se decidió no añadirla mientras no aparezca el caso, porque el QR ya lleva
la IP y en la práctica el emparejamiento inicial funciona aunque mDNS no.

**4. Sin cola de clips, por decisión del plan.** Si no hay conexión al enviar, el clip se
pierde. Es lo correcto —algo copiado hace horas apareciendo de pronto sería peor— pero
conviene comprobar en uso real que el aviso «Sin conexión con [PC]» se ve lo bastante.

**5. El instalador no está firmado.** SmartScreen avisará en cada instalación. Un
certificado de firma de código cuesta dinero; mientras no lo haya, la guía explica cómo
saltarse el aviso.

**6. Kotlin y C# comparten protocolo pero no código.** Cualquier cambio futuro hay que
hacerlo dos veces. Los vectores compartidos y la prueba cruzada detectarían una divergencia,
pero solo si se ejecutan: conviene lanzar `scripts/prueba-cruzada.ps1` antes de cada
versión.

## Orden recomendado de las pruebas manuales

Están ordenadas para que cada una construya sobre la anterior y para que un fallo temprano
ahorre el resto.

**Primero, lo que desbloquea todo lo demás:**

1. **M6.1** — crear la keystore de firma. Sin ella no hay APK que instalar en un móvil real.
2. **M1.1** — emparejar por primera vez, con el APK ya firmado. Si esto falla, nada más
   tiene sentido.
3. **M6.2** — comprobar que el APK **de publicación** empareja y mueve clips. Es la
   verificación de que R8 no ha roto nada.

**Después, lo que la gente usa a diario:**

4. **M2.1** — copiar en el PC y pegar en el móvil.
5. **M3.1** — el botón de la notificación. *La prueba que no se puede saltar*: si la lectura
   del portapapeles se hiciera en el momento equivocado, aquí llegaría un clip vacío.
6. **M3.2** — compartir sin tocar el portapapeles.
7. **M3.3** — que una contraseña **no** salga del móvil.

**Luego, lo que hace que siga funcionando mañana:**

8. **M1.4** — reconexión tras cortar el WiFi.
9. **M5.2** y **M5.3** — cambios de red y WiFi apagado un buen rato.
10. **M5.1** — suspender el PC y despertarlo.
11. **M5.4** y **M5.5** — reiniciar el móvil y matar el proceso.
12. **M2.4** y **M5.8** — media hora y una noche entera en segundo plano. Son las que más
    tardan y las que más dicen sobre si la app es usable de verdad.

**Al final, lo que se hace una vez:**

13. **M4.1**, **M4.2**, **M4.3** — emparejar escaneando, caducidad y desemparejar.
14. **M4.4** — multi-PC, si hay un segundo PC a mano.
15. **M1.3** — Wireshark, para ver con los propios ojos que no viaja nada legible.
16. **M6.3** — instalación limpia siguiendo solo la guía, en un equipo virgen.

**Consejo sobre M5.8 y M2.4**: son las dos pruebas largas y conviene empezarlas pronto y en
paralelo con el resto, no dejarlas para el final.

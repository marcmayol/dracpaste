# DracPaste: portapapeles compartido Android <-> Windows (privacidad primero)

> Documento de planificación para Claude Code. Contiene contexto, decisiones cerradas,
> arquitectura, protocolo, fases con criterios de aceptación y riesgos conocidos.
> Leer entero antes de escribir código.

---

## 1. Visión y principios

**Nombre del producto: DracPaste** (familia de apps "Drac"). Paquete Android:
`com.<dominio>.dracpaste`. Servicio mDNS: `_dracpaste._tcp`. Usar "DracPaste" en
notificación, tray, ventanas y documentación.

App para compartir el portapapeles entre Android y Windows de la forma más instantánea
posible, con privacidad como valor central:

- **Cero nube, cero cuenta**: comunicación directa en LAN; la identidad es el par de
  dispositivos emparejados.
- **Cifrado E2E siempre**, incluso en LAN.
- **Sin analytics ni telemetría.**
- **Transparencia con las limitaciones de Android**: la app explica por qué la dirección
  Android -> Windows requiere un gesto.

### Asimetría de diseño (decisión cerrada)

| Dirección | Comportamiento |
|---|---|
| Windows -> Android | Automático total: copias en el PC y aparece en el portapapeles del móvil. |
| Android -> Windows | Semiautomático: botón en notificación persistente (lee el portapapeles) o share sheet (envía el texto sin pasar por el portapapeles). |

Motivo: desde Android 10 una app solo puede leer el portapapeles con foco o siendo IME.
No intentar workarounds con `READ_LOGS`, root ni ADB. La vía IME está descartada
(ver sección 6).

---

## 2. Stack y estructura

- **Android**: Kotlin nativo, Jetpack Compose para las pantallas mínimas.
  `minSdk 29` (Android 10), `targetSdk` vigente.
- **Windows**: C# (.NET 8), app de bandeja (tray) con WinForms/WPF mínimo para ajustes
  y emparejamiento. Se elige C# frente a Rust para no abrir dos frentes; el reto del
  proyecto está en Android.
- **Criptografía**: libsodium en ambos lados (`lazysodium-android` en Kotlin;
  `libsodium-net` o `NSec` en C#). X25519 para intercambio de claves,
  ChaCha20-Poly1305 para los mensajes.
- El módulo de red y protocolo de Android se estructura como librería Kotlin
  independiente del resto de la app (reutilizable en el futuro).

Monorepo:

```
/android    -> proyecto Android Studio (Kotlin)
/windows    -> solución .NET (C#)
/docs       -> protocolo, decisiones, guía de emparejamiento
PLAN.md     -> este documento
PROGRESS.md -> fases completadas y pendientes (lo mantiene Claude Code al cerrar cada fase)
```

---

## 3. Arquitectura

### 3.1 Android

1. **ForegroundService** (tipo `connectedDevice`, obligatorio declararlo en Android 14+):
   - Mantiene el socket persistente con el PC activo.
   - Muestra la notificación persistente con el botón "Enviar portapapeles" y el estado
     de conexión.
   - Al recibir un clip del PC lo escribe vía `ClipboardManager`. Fallback si algún OEM
     lo bloquea: actualizar la notificación con "Toca para pegar" (acción que abre
     activity transparente que escribe el clip).
2. **Activity transparente de captura** (tema translúcido, sin animación,
   `excludeFromRecents`):
   - Lanzada por el botón de la notificación.
   - **CRÍTICO**: leer el portapapeles en `onWindowFocusChanged(hasFocus = true)`,
     NO en `onCreate` (el foco llega después; en `onCreate` el clip llega vacío).
   - Lee, cifra, envía por el socket ya abierto del servicio, confirma (toast o
     actualización de notificación) y hace `finish()`. Objetivo: ciclo < 300 ms percibidos.
3. **Share target**: activity que recibe `ACTION_SEND` con `text/plain` y envía
   directamente sin tocar el portapapeles.
4. **Pantalla de emparejamiento**: escáner QR (CameraX + ML Kit barcode o ZXing).
5. **Pantalla de ajustes**: lista de dispositivos emparejados, **selector de PC activo
   (destino único de sincronización)**, modo solo LAN (ON, mostrado como valor en v1),
   caducidad de clips, filtro de sensibles, pausar sincronización.

Obligatorio además:
- Respetar `ClipDescription.EXTRA_IS_SENSITIVE`: clips sensibles (gestores de
  contraseñas) NO se sincronizan nunca.
- Exención de optimización de batería con explicación; guía específica para
  Xiaomi/Samsung dentro de la app.
- Permisos: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `CAMERA` (solo emparejamiento).

### 3.2 Windows

1. **App de bandeja** sin ventana principal. Menú: estado, emparejar, ajustes, salir.
2. **Listener de portapapeles**: ventana oculta (message-only) con
   `AddClipboardFormatListener`; al recibir `WM_CLIPBOARDUPDATE`, leer y empujar al
   móvil. Sin polling.
   - Debounce de ~100 ms (algunas apps disparan varios eventos por copia).
   - Anti-eco: registrar un formato de portapapeles propio como marcador en los clips
     que escribe la app, o comparar hash del último clip recibido.
3. **Escritura**: al recibir clip del móvil, escribirlo directamente.
4. **Ventana de emparejamiento**: genera y muestra el QR (clave pública + IP + puerto
   + token).
5. **Reanudación de suspensión**: con `SystemEvents.PowerModeChanged`, reanunciar el
   servicio mDNS y reiniciar el listener de socket.
6. **Arranque con Windows** opcional (clave Run del registro).

### 3.3 Red y descubrimiento

- **Descubrimiento**: mDNS/DNS-SD. Android: `NsdManager`. Windows: publicar
  `_dracpaste._tcp` (librería tipo `Makaretu.Dns`), con `device_id` en los registros TXT.
  Reconexión automática aunque cambien las IPs.
- **Transporte**: TCP persistente; el móvil inicia, el PC escucha. Reconexión con
  backoff exponencial (1 s -> 30 s máx).
- **Keepalive**: PING/PONG cada 15 s; sin PONG en 10 s -> desconectado, actualizar
  estado en notificación/tray.
- **v1 solo LAN.** Relay cifrado o P2P queda para v2; los mensajes ya van cifrados E2E
  independientemente del transporte.
- **Multi-PC (decisión cerrada).** La app soporta múltiples PCs emparejados en la
  estructura de datos (lista de dispositivos, cada uno con su clave), pero la política
  de v1 es "destino activo": un único PC activo a la vez, seleccionable en ajustes.
  Los anuncios mDNS de PCs no emparejados o no activos se ignoran. La clave de cifrado
  es por par (móvil <-> PC concreto), derivada en cada emparejamiento: desemparejar o
  comprometer un PC no afecta a los demás.

---

## 4. Protocolo (v1)

Framing: `[longitud uint32 BE][payload]`. Payload cifrado salvo durante el handshake
de emparejamiento.

### 4.1 Emparejamiento (una vez por par)

1. El PC genera par X25519 persistente (identidad) y un token efímero.
2. QR con JSON: `{ "v":1, "pk": <base64>, "ip": "...", "port": ..., "token": "..." }`.
3. El móvil escanea, conecta y envía `PAIR_REQUEST { pk_movil, token }` (el token
   demuestra presencia física ante el QR).
4. Ambos derivan clave compartida (X25519) y confirman con `PAIR_CONFIRM` cifrado.
   Se muestra huella corta de ambas claves en ambas pantallas (TOFU con verificación
   visual opcional).
5. Persistir la clave pública del otro. Claves privadas: Android Keystore / DPAPI
   en Windows.

### 4.2 Sesión

Mensajes cifrados con ChaCha20-Poly1305, nonce incremental por dirección:

- `HELLO { device_id, version }` -> `HELLO_ACK`
- `CLIP { type: "text/plain", payload, timestamp_ms, origin_id }`
  (`origin_id` = hash del contenido, para descartar ecos)
- `PING` / `PONG`
- `UNPAIR` (borra claves en ambos lados)
- `BYE`

Anti-bucle: al escribir un clip recibido, guardar su `origin_id`; si el listener local
detecta un cambio cuyo hash coincide con el último recibido, no reenviar.

v1 solo `text/plain`; el campo `type` deja preparados imágenes y ficheros para v2.

### 4.3 Máquina de estados de conexión (móvil)

Estados: `SIN_EMPAREJAR -> BUSCANDO -> CONECTANDO -> CONECTADO -> RECONECTANDO`.
La notificación refleja siempre el estado.

- `BUSCANDO`: descubrimiento NSD activo filtrando `_dracpaste._tcp`; solo se atiende el
  anuncio cuyo `device_id` (TXT) coincida con el PC activo. Modo pasivo (solo escucha,
  sin reintentos) cuando el PC no está en la red.
- `CONECTANDO`: socket TCP + handshake; si las claves no cuadran, cortar e ignorar
  ese anuncio.
- `CONECTADO`: PING cada 15 s; sin PONG en 10 s -> cerrar socket -> `RECONECTANDO`.
- `RECONECTANDO`: en paralelo, reintentos contra la última IP conocida con backoff
  exponencial (1 s -> 30 s máx) y mDNS reactivado por si cambió la IP. Lo primero que
  funcione gana.
- Triggers adicionales de reintento: `NetworkCallback` al cambiar de red (cerrar socket
  limpio -> `BUSCANDO`), `ACTION_SCREEN_ON` al desbloquear (mitiga Doze sin WakeLocks),
  `BOOT_COMPLETED` al reiniciar.
- **Sin cola de clips**: si no hay conexión al enviar, la notificación indica
  "Sin conexión con [PC]" y no se guarda nada pendiente, salvo retener en memoria el
  último envío fallido 60 s por si la reconexión es inmediata. Tras reconectar no hay
  "puesta al día"; se empieza desde el siguiente evento.

---

## 5. Fases de desarrollo

> Orden pensado para validar lo arriesgado (red + cifrado) antes de pulir UX.
> Cada fase cierra con sus criterios de aceptación verificados manualmente y una
> entrada en PROGRESS.md.

### Fase 0: Esqueleto
- Monorepo, proyectos Android y .NET compilando vacíos.
- Protocolo (sección 4 completa) copiado a `/docs/protocol.md`.
- **Aceptación**: ambos proyectos compilan; README con instrucciones de build.

### Fase 1: Túnel (descubrimiento + socket + cifrado)
- Windows: servidor TCP + mDNS + generación de claves + contenido del QR en texto.
- Android: activity pelada, descubrimiento NSD, handshake (token pegado a mano si aún
  no hay escáner), sesión cifrada, mensaje de prueba.
- **Aceptación**: texto viaja cifrado en ambas direcciones; Wireshark confirma payload
  ilegible; reconexión automática tras cortar WiFi.

### Fase 2: Windows -> Android completo
- Listener con debounce y anti-eco; escritura en portapapeles Android desde el
  servicio; notificación básica de estado.
- **Aceptación**: copiar en PC y pegar en móvil en < 1 s; sin bucles al copiar
  repetidamente; el servicio sobrevive 30 min en background con pantalla apagada.

### Fase 3: Android -> Windows
- Botón de notificación -> activity transparente -> lectura en `onWindowFocusChanged`
  -> envío -> `finish()`. Share target `ACTION_SEND`. Anti-eco en Windows.
- **Aceptación**: desde cualquier app, botón y pegar en PC en < 1 s; share sheet
  funciona sin tocar el portapapeles; un clip sensible de un gestor de contraseñas
  NO se envía.

### Fase 4: Emparejamiento pulido y multi-PC
- QR real en Windows, escáner en Android.
- Lista de dispositivos emparejados con selector de PC activo; desemparejar
  (mensaje `UNPAIR`, borra claves en ambos lados); huellas visibles.
- **Aceptación**: emparejar desde cero en < 30 s sin teclear; desemparejar impide
  reconexión; con dos PCs emparejados, los clips van solo al activo y cambiar de
  activo surte efecto sin reiniciar.

### Fase 5: Robustez y ajustes
- Exención de batería con diálogo; guía Xiaomi/Samsung; ajustes completos (3.1.5);
  estados claros en notificación y tray; máquina de estados completa (4.3) con todos
  sus triggers.
- **Aceptación (medibles)**:
  - Suspender el PC y reanudarlo -> reconexión < 5 s sin intervención.
  - Cambiar el móvil de WiFi y volver a la misma red -> reconexión < 5 s.
  - WiFi del móvil apagado 10 min y encendido -> reconexión < 10 s.
  - Reiniciar el móvil -> el servicio vuelve solo.
  - Matar el proceso en Android -> el servicio se restaura.
  - El usuario entiende el estado en todo momento mirando notificación/tray.

### Fase 6: Empaquetado
- Instalador Windows (MSIX o Inno Setup) + arranque opcional con Windows.
- APK firmado para distribución directa (Play Store se evalúa después por las
  políticas de foreground service).
- Documentación de usuario final en `/docs`.
- **Aceptación**: instalación limpia en máquina y móvil vírgenes siguiendo solo la
  documentación.

---

## 6. Fuera de alcance (v1)

- Sincronización fuera de la LAN (relay cifrado o P2P): v2.
- Imágenes y ficheros: v2 (el protocolo ya lo contempla vía campo `type`).
- **Modo IME: descartado** (no "futuro"). Requiere ser el teclado por defecto del
  sistema; incompatible con conservar Gboard (cerrado, sin API de extensión), y los
  forks open source (HeliBoard, FlorisBoard) no igualan su predicción multilingüe
  ES/CA/EN ni GIFs/stickers. Se reevaluaría solo si cambian esas condiciones.
- Historial de clips: futuro, siempre cifrado local y opt-in.
- macOS/Linux/iOS: no planificado.

## 7. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Clip vacío en la activity transparente | Leer SIEMPRE en `onWindowFocusChanged`; test manual obligatorio en Fase 3 |
| OEMs matan el foreground service | Exención de batería + guía por fabricante + `START_STICKY` + tipo de servicio correcto |
| Bucle de eco entre portapapeles | `origin_id` por hash + marcador propio en Windows; probado en Fases 2 y 3 |
| Escritura bloqueada en background en algún OEM | Fallback: notificación "Toca para pegar" |
| mDNS bloqueado (client isolation) | Timeout de descubrimiento -> conexión manual por IP:puerto |
| Varios eventos por una copia en Windows | Debounce 100 ms + comparación de hash |
| Foreground service desde boot restringido en Android 14+/OEMs | `connectedDevice` está permitido; probar explícitamente en Fase 5 |
| Impostor en la LAN publicando el mismo servicio mDNS | El handshake verifica claves del par; si no cuadran, se corta y se ignora |

## 8. Convenciones para Claude Code (modo autónomo)

- Ejecutar las fases en orden, de la 0 a la 6, sin esperar confirmación humana.
  No parar salvo bloqueo técnico irresoluble; en ese caso, documentarlo en
  PROGRESS.md, saltar a lo que no dependa del bloqueo y seguir.
- Autoverificación por fase: compilar ambos proyectos, escribir y ejecutar tests
  unitarios de lo testeable sin hardware (framing y cifrado del protocolo,
  anti-eco por origin_id, máquina de estados con transiciones simuladas,
  serialización de mensajes, lógica de emparejamiento con claves de prueba).
  Una fase no se da por cerrada si algo no compila o un test falla.
- Los criterios de aceptación que requieren hardware real (móvil físico, WiFi,
  suspensión del PC, latencias) NO se marcan como cumplidos: se listan en
  PROGRESS.md bajo "Pruebas manuales pendientes", con pasos exactos y resultado
  esperado, agrupadas por fase.
- Mantener PROGRESS.md al día al cerrar cada fase: qué se hizo, qué se verificó
  automáticamente, qué queda para prueba manual, y cualquier decisión tomada
  sobre la marcha con su porqué.
- Al terminar todo, escribir un resumen final en PROGRESS.md: estado global,
  riesgos detectados durante la implementación y orden recomendado de las
  pruebas manuales.
- Commits por fase y componente, mensajes convencionales (`feat:`, `fix:`...).
- No añadir dependencias fuera de las indicadas sin justificarlo antes en
  `/docs/decisions.md`.
- Cualquier desviación del protocolo se refleja primero en `/docs/protocol.md` y
  después en el código de ambos lados en el mismo cambio.

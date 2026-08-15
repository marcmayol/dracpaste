# Protocolo DracPaste v1

Especificación normativa del diálogo entre el móvil (Android) y el PC (Windows).
Ambas implementaciones deben ceñirse a este documento; cualquier cambio se escribe
**aquí primero** y después en el código de los dos lados, en el mismo cambio
(`PLAN.md` §8).

La sección 4 de `PLAN.md` es la fuente de esta especificación. Aquí se añade el detalle
exacto —bytes, orden, longitudes, derivación de claves— que hace falta para que dos
implementaciones escritas en lenguajes distintos se entiendan sin ambigüedad.

- **Versión del protocolo**: `1`
- **Servicio mDNS**: `_dracpaste._tcp`
- **Puerto**: dinámico, anunciado por mDNS (por defecto se intenta el 47653)
- **El móvil es el cliente**, el PC es el servidor. Siempre.

---

## 1. Framing

Todo lo que viaja por el socket TCP son *frames*:

```
[ longitud : uint32 big-endian ][ payload : longitud bytes ]
```

- `longitud` es el tamaño del payload, sin contar los 4 bytes de la propia longitud.
- **Máximo 1 MiB** (`1048576`). Un frame mayor es un error de protocolo: se cierra la
  conexión sin contestar. Protege contra un `length` malicioso que reserve memoria.
- Un `longitud` de 0 es un error de protocolo.

El payload va cifrado **salvo** en los frames marcados explícitamente como "en claro"
(los del handshake, que son los que sirven para acordar la clave).

---

## 2. Criptografía

| Cometido | Algoritmo |
|---|---|
| Acuerdo de claves | X25519 (RFC 7748) |
| Derivación | HKDF-SHA256 (RFC 5869) |
| Cifrado autenticado | ChaCha20-Poly1305 IETF (RFC 8439), nonce de 12 bytes, tag de 16 |

Se evitan a propósito las construcciones propias de libsodium (`crypto_box`, `crypto_kx`)
para que cualquier librería estándar pueda implementar este protocolo. Ver `decisions.md`
D-003.

### 2.1 Identidad

Cada dispositivo tiene un par X25519 **persistente** de 32 bytes por clave, generado en la
primera ejecución:

- **Android**: la clave privada se guarda envuelta por el Android Keystore.
- **Windows**: la clave privada se guarda cifrada con DPAPI (ámbito usuario actual).

Además, cada dispositivo tiene un `device_id`: 16 bytes aleatorios en hex minúscula,
generados una vez por instalación. **No deriva de ningún identificador de hardware**
(ver `decisions.md` D-006).

### 2.2 Clave de par (`K_pair`)

Se calcula una vez al emparejar y se puede recalcular siempre a partir de las claves
guardadas:

```
shared  = X25519(privada_propia, publica_del_otro)          // 32 bytes
pk_lo   = min(pk_movil, pk_pc)   comparando byte a byte     // 32 bytes
pk_hi   = max(pk_movil, pk_pc)                              // 32 bytes

K_pair  = HKDF-SHA256(
              ikm  = shared,
              salt = "DracPaste/v1/pair"  (ASCII, 17 bytes),
              info = pk_lo || pk_hi       (64 bytes),
              L    = 32 )
```

Ordenar las claves públicas hace que los dos lados obtengan el mismo `K_pair` sin depender
de quién es cliente y quién servidor.

`K_pair` **nunca se usa para cifrar mensajes**. Solo es el material del que salen las
claves de sesión.

### 2.3 Claves de sesión

Se derivan en **cada** conexión, a partir de los dos retos aleatorios que se intercambian
en el handshake (§4):

```
salt   = challenge_movil (16 B) || challenge_pc (16 B)      // 32 bytes

K_m2p  = HKDF-SHA256(ikm = K_pair, salt = salt, info = "DracPaste/v1/m2p", L = 32)
K_p2m  = HKDF-SHA256(ikm = K_pair, salt = salt, info = "DracPaste/v1/p2m", L = 32)
```

**Por qué claves por sesión y no `K_pair` directamente**: con ChaCha20-Poly1305, repetir el
par (clave, nonce) rompe la confidencialidad por completo. Si se cifrara con `K_pair`, cada
reconexión —y hay muchas: cambios de red, suspensión, Doze— reiniciaría el contador de
nonces a cero y reutilizaría combinaciones ya usadas. Con claves derivadas de retos frescos,
cada sesión es un espacio de nonces nuevo y el contador puede empezar en 0 sin riesgo.

Una clave distinta por dirección evita además que un mensaje del PC pueda reinyectarse como
si viniera del móvil.

### 2.4 Nonces

```
nonce = [ 0x00 0x00 0x00 0x00 ][ contador : uint64 big-endian ]     // 12 bytes
```

- Cada dirección lleva su propio contador, que empieza en **0** y se incrementa en 1 por
  cada frame cifrado enviado.
- El receptor **rechaza** un frame cuyo contador no sea estrictamente mayor que el último
  aceptado en esa dirección. Un contador repetido o hacia atrás es un intento de repetición:
  se cierra la conexión.
- El AAD (datos autenticados adicionales) es **vacío**.

### 2.5 Formato del frame cifrado

El payload de un frame cifrado es:

```
[ contador : uint64 big-endian ][ ciphertext || tag Poly1305 ]
```

El contador viaja en claro (8 bytes) para que el receptor pueda reconstruir el nonce; va
autenticado implícitamente, porque un contador alterado produce un nonce distinto y el tag
no verifica.

El texto plano resultante es un objeto **JSON en UTF-8** (`decisions.md` D-004).

### 2.6 Huella de verificación

Para que el usuario pueda comparar visualmente lo que ve en el móvil y en el PC:

```
huella = SHA-256(pk_lo || pk_hi)
```

Se muestran los **4 primeros bytes** en hex mayúscula, en dos grupos: `A3F2-9C71`.
Los dos dispositivos de un par muestran siempre la misma huella.

---

## 3. Emparejamiento

Ocurre una vez por par de dispositivos.

### 3.1 El QR

El PC genera un **token efímero** de 16 bytes aleatorios, válido **120 segundos** y de un
solo uso, y muestra un QR con este JSON:

```json
{ "v": 1, "pk": "<base64 de 32 bytes>", "ip": "192.168.1.40", "port": 47653,
  "token": "<base64 de 16 bytes>", "name": "PC-DESPACHO", "device_id": "<hex 16 bytes>" }
```

El token demuestra presencia física ante la pantalla del PC. Es lo único que impide que
alguien de la LAN se empareje por su cuenta.

### 3.2 Diálogo

Sobre una conexión TCP recién abierta contra `ip:port`:

**1. Móvil → PC — `PAIR_REQUEST` (en claro)**

```json
{ "t": "PAIR_REQUEST", "v": 1, "pk": "<base64 32 B>",
  "device_id": "<hex 16 B>", "name": "Pixel", "token": "<base64 16 B>" }
```

El PC comprueba el token (existe, no caducado, no usado). Si falla: cierra el socket sin
contestar y **no** revela el motivo.

**2. Ambos** derivan `K_pair` (§2.2) y, con los retos incluidos abajo, las claves de sesión.
Para simplificar, el emparejamiento usa como retos:

```
challenge_movil = SHA-256("DracPaste/v1/pairing" || token)[0..16]
challenge_pc    = SHA-256("DracPaste/v1/pairing" || token)[16..32]
```

es decir, ambos derivan de forma determinista del token, que ya es aleatorio y de un solo
uso. Así el emparejamiento no necesita un intercambio de retos aparte.

**3. PC → móvil — `PAIR_CONFIRM` (cifrado con `K_p2m`, contador 0)**

```json
{ "t": "PAIR_CONFIRM", "device_id": "<hex>", "name": "PC-DESPACHO",
  "fingerprint": "A3F2-9C71" }
```

Que el móvil consiga descifrarlo demuestra que el PC tiene la misma `K_pair`.

**4. Móvil → PC — `PAIR_ACK` (cifrado con `K_m2p`, contador 0)**

```json
{ "t": "PAIR_ACK", "fingerprint": "A3F2-9C71" }
```

**5.** Ambos persisten la clave pública, el `device_id` y el nombre del otro. El PC invalida
el token. Se cierra la conexión: la sesión de trabajo se abre después con el handshake
normal (§4).

Si en el paso 3 o 4 el descifrado falla, o las huellas no coinciden, ambos lados abortan y
**no** guardan nada.

---

## 4. Handshake de sesión

Cada vez que el móvil abre un socket contra un PC ya emparejado:

**1. Móvil → PC — `HELLO` (en claro)**

```json
{ "t": "HELLO", "v": 1, "device_id": "<hex 16 B>", "challenge": "<base64 16 B>" }
```

El PC busca ese `device_id` entre sus emparejados. Si no lo conoce, cierra el socket.

**2. PC → móvil — `SERVER_HELLO` (en claro)**

```json
{ "t": "SERVER_HELLO", "v": 1, "device_id": "<hex 16 B>", "challenge": "<base64 16 B>" }
```

Si el `device_id` del PC no es el que el móvil esperaba (PC activo), el móvil cierra y
sigue buscando: eso descarta a un impostor que anuncie el mismo servicio mDNS.

**3. Ambos** derivan `K_m2p` y `K_p2m` (§2.3) y ponen sus contadores a 0.

**4. Móvil → PC — `AUTH` (cifrado, contador 0)**

```json
{ "t": "AUTH", "echo": "<base64 del challenge del PC>" }
```

**5. PC → móvil — `AUTH_OK` (cifrado, contador 0)**

```json
{ "t": "AUTH_OK", "echo": "<base64 del challenge del móvil>" }
```

Un eco que no coincide, o un descifrado que falla, cierra la conexión. Al terminar el paso
5, ambos saben que el otro posee `K_pair`: la sesión está autenticada en las dos
direcciones.

**Plazo**: si el handshake no se completa en **10 segundos**, se cierra la conexión.

---

## 5. Mensajes de sesión

Todos cifrados, todos JSON UTF-8. El campo `t` identifica el tipo.

### `CLIP`

```json
{ "t": "CLIP", "type": "text/plain", "payload": "<base64 del texto en UTF-8>",
  "timestamp_ms": 1755100000000, "origin_id": "<hex 16 B>" }
```

- v1 solo acepta `type` = `text/plain`. Un `type` desconocido **se ignora en silencio**
  (no es un error: es un cliente más nuevo hablando de imágenes, que llegan en v2).
- `origin_id` = **SHA-256 del texto en UTF-8, truncado a 16 bytes**, en hex minúscula.
- **Tamaño máximo del texto: 256 KiB** antes de codificar. Un clip mayor no se envía; el
  emisor lo descarta e informa en su interfaz.
- Un `payload` vacío no se envía.

### `PING` / `PONG`

```json
{ "t": "PING", "seq": 7 }
{ "t": "PONG", "seq": 7 }
```

Cada 15 s. Quien envía `PING` espera el `PONG` con el mismo `seq` en **10 s**; si no llega,
da la conexión por muerta y cierra el socket.

### `UNPAIR`

```json
{ "t": "UNPAIR" }
```

Quien lo recibe borra la clave pública y el `device_id` del emisor y cierra. Quien lo envía
hace lo mismo tras enviarlo. No se contesta.

### `BYE`

```json
{ "t": "BYE" }
```

Cierre ordenado (el PC lo manda al suspenderse o salir). No implica desemparejar.

---

## 6. Anti-eco

El bucle que hay que evitar: el PC envía un clip, el móvil lo escribe en su portapapeles,
el listener del móvil lo detecta como cambio y lo devuelve al PC, y así indefinidamente.

Regla, **idéntica en los dos lados**:

1. Antes de escribir en el portapapeles un clip recibido, se guarda su `origin_id` como
   "último recibido".
2. Cuando el listener local detecta un cambio, calcula el `origin_id` del contenido nuevo.
   Si coincide con el último recibido, **no se reenvía** y se limpia la marca.
3. La marca caduca a los **5 segundos**: pasado ese tiempo, si el usuario vuelve a copiar
   ese mismo texto a mano, es una copia legítima y debe viajar.

Windows añade una segunda barrera independiente: registra el formato de portapapeles
`DracPasteOrigin` y lo incluye en todo lo que escribe. Si un `WM_CLIPBOARDUPDATE` trae ese
formato, el cambio es obra propia y se ignora sin más comprobaciones. Dos mecanismos
distintos porque el fallo de uno solo produce un bucle infinito visible para el usuario.

Windows aplica además un **debounce de 100 ms**: varias aplicaciones disparan más de un
`WM_CLIPBOARDUPDATE` por una sola copia.

---

## 7. Vectores de prueba

Estas constantes están fijadas para que Kotlin y C# demuestren que producen los mismos
bytes **sin necesidad de conectarlos**. Los dos lados tienen un test que las comprueba; si
alguna implementación se desvía, el test se pone en rojo (ver `decisions.md` D-003).

Todo en hex minúscula.

```
# Claves privadas X25519 de prueba (NO usar fuera de los tests)
priv_movil = 77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a
priv_pc    = 5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb

# Públicas correspondientes (RFC 7748 §6.1)
pub_movil  = 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a
pub_pc     = de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f

# Secreto compartido X25519
shared     = 4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742
```

Aplicando §2.2 y §2.3 con
`challenge_movil = 000102030405060708090a0b0c0d0e0f` y
`challenge_pc = 101112131415161718191a1b1c1d1e1f`:

```
K_pair     = 7619334a99c42574fe2818c1166864c68727a329fb58f8647f2f6f61d6024c74
K_m2p      = f0dbcb2507a2f78763fb7fda468ffc6a9fc8a55630153130d0725f5ac54d66f3
K_p2m      = bd975ac0e20687bfa1dd130670c6659a2a1f8854fa1c924870f5e482814a4715

# ChaCha20-Poly1305 de "hola" (UTF-8) con K_m2p y contador 0 -> ciphertext || tag
cifrado    = 678e67f72a09b0970f17bb20686f7545b9f5b1bb

# Huella de verificacion (§2.6)
huella     = 9962-5B51

# Retos de emparejamiento (§3.2) para token = 0f0e0d0c0b0a09080706050403020100
reto_movil = 5af8673472d05d3ccd761485d419b651
reto_pc    = 6bbd531bbad346bd2162feb25261c2e2

# origin_id de "hola" (§5). Si los dos lados no calculan el mismo, el anti-eco no
# funciona y los clips rebotan entre el movil y el PC.
origin_id  = b221d9dbb083a7f33428d7c2a3c3198a
```

Estos valores están replicados en los ficheros de test de ambos lados
(`VectoresProtocoloTest` en Kotlin, `VectoresProtocoloTests` en C#) y **deben coincidir**.
Si una implementación se desvía, su test se pone en rojo sin necesidad de conectar los dos
dispositivos.

---

## 8. Máquina de estados de conexión (móvil)

```
SIN_EMPAREJAR ──emparejar──> BUSCANDO
BUSCANDO ──anuncio mDNS del PC activo / IP conocida──> CONECTANDO
CONECTANDO ──handshake OK──> CONECTADO
CONECTANDO ──fallo──> RECONECTANDO
CONECTADO ──socket muerto / sin PONG──> RECONECTANDO
RECONECTANDO ──éxito──> CONECTADO
RECONECTANDO ──cambio de red──> BUSCANDO
cualquiera ──desemparejar──> SIN_EMPAREJAR
```

- `BUSCANDO`: descubrimiento NSD filtrando `_dracpaste._tcp`; solo se atiende el anuncio
  cuyo `device_id` (registro TXT) sea el del PC activo. Modo pasivo (solo escucha, sin
  reintentos) cuando el PC no está en la red.
- `RECONECTANDO`: **en paralelo**, reintentos contra la última IP conocida con backoff
  exponencial (1 s → 30 s máximo) y mDNS reactivado por si cambió la IP. Gana lo primero
  que funcione.
- Triggers adicionales de reintento: `NetworkCallback` al cambiar de red (cierre limpio →
  `BUSCANDO`), `ACTION_SCREEN_ON` al desbloquear (mitiga Doze sin `WakeLock`),
  `BOOT_COMPLETED` al reiniciar.
- **Sin cola de clips**: si no hay conexión al enviar, se informa ("Sin conexión con [PC]")
  y no se guarda nada pendiente, salvo retener en memoria el último envío fallido **60 s**
  por si la reconexión es inmediata. Tras reconectar no hay puesta al día.

---

## 9. Registros TXT de mDNS

El PC publica `_dracpaste._tcp` con:

| Clave | Valor |
|---|---|
| `v` | `1` |
| `id` | `device_id` del PC, hex |
| `name` | nombre legible del PC |

El móvil usa `id` para reconocer a su PC activo aunque haya cambiado de IP, y para ignorar
cualquier otro anuncio.

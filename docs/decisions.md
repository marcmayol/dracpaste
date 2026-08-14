# Decisiones de implementación

Registro de decisiones tomadas durante el desarrollo que no estaban cerradas en `PLAN.md`,
o que se desvían de él. Cada entrada dice **qué**, **por qué** y **qué alternativa se descartó**.

El plan (sección 8) exige justificar aquí cualquier dependencia fuera de las indicadas
**antes** de usarla.

---

## D-001 · SDK de .NET instalado per-user, no a nivel de sistema

**Fecha**: 2026-08-14 · **Fase**: 0

La máquina tenía el *runtime* de .NET 8 (8.0.30, incluido `Microsoft.WindowsDesktop.App`)
pero **no el SDK**, así que el lado Windows no compilaba.

Se instaló el SDK 8.0.424 con el script oficial `dotnet-install.ps1` en
`C:\Users\marcm\.dotnet`, con `-NoPath`:

- No requiere permisos de administrador.
- No modifica el `PATH` del sistema ni ninguna configuración de Windows.
- Es reversible borrando esa carpeta.

**Consecuencia para quien compile**: el `dotnet` del `PATH` es el de
`C:\Program Files\dotnet` (solo runtime). Para compilar hay que invocar
`%USERPROFILE%\.dotnet\dotnet.exe` o añadir esa carpeta al `PATH` de la sesión.
El `README.md` lo documenta.

**Descartado**: instalar el SDK con `winget` a nivel de máquina, porque toca la
configuración del equipo del usuario sin su presencia.

---

## D-002 · El módulo de protocolo de Android es JVM puro, no una librería Android

**Fecha**: 2026-08-14 · **Fase**: 0

`PLAN.md` §2 pide que el módulo de red y protocolo sea "una librería Kotlin independiente
del resto de la app". Se lleva al extremo: `:protocolo` es un módulo **`kotlin-jvm`**, sin
el SDK de Android en el classpath.

**Por qué**:

- El modo autónomo (§8) exige tests unitarios de framing, cifrado, anti-eco y máquina de
  estados **sin hardware**. En un módulo JVM puro corren en el JVM local en segundos; en
  una librería Android que dependa de código nativo tendrían que ser tests instrumentados
  y harían falta emulador o móvil.
- Al no tener Android en el classpath, el compilador **impide** que el protocolo importe
  `android.*` por descuido. La independencia deja de ser un acuerdo y pasa a ser una regla
  que la build hace cumplir. Es el mismo criterio que ya se usó en DracPDF-Android con su
  módulo `dominio`.
- Hace el módulo reutilizable de verdad (un futuro cliente de escritorio en JVM lo usaría
  tal cual).

**Consecuencia**: todo lo que sea Android (NSD, sockets con `Network`, `ClipboardManager`,
Keystore) vive en `:app` detrás de interfaces declaradas en `:protocolo`.

---

## D-003 · Criptografía: Bouncy Castle en Kotlin, NSec en C#

**Fecha**: 2026-08-14 · **Fase**: 0 · **Desviación de `PLAN.md` §2**

El plan pide libsodium en ambos lados (`lazysodium-android` en Kotlin, `libsodium-net` o
`NSec` en C#).

**Lado Windows: se cumple el plan.** Se usa `NSec.Cryptography`, que envuelve libsodium y
trae los binarios nativos en el propio paquete NuGet. Es testeable con xUnit en el JVM...
perdón, en el runtime de .NET, sin hardware ni permisos especiales.

**Lado Kotlin: se desvía.** Se usa `org.bouncycastle:bcprov-jdk18on` en lugar de
`lazysodium-android`:

| | lazysodium-android | Bouncy Castle |
|---|---|---|
| Naturaleza | AAR con `.so` nativas por ABI (vía JNA) | Java puro |
| ¿Corre en un módulo JVM puro? | No | Sí |
| ¿Tests sin emulador? | No | Sí |
| Peso en el APK | ~4 MB de nativos por ABI | ~5 MB de jar, reducible con R8 |

Usar lazysodium obligaría a que `:protocolo` fuese una librería Android (rompiendo D-002)
y a que **todos** los tests de cifrado necesitasen un dispositivo, que es justo lo que el
modo autónomo no puede ejecutar.

**Esto no compromete la interoperabilidad**: los dos algoritmos del plan son estándares
públicos con vectores de prueba definidos —X25519 (RFC 7748) y ChaCha20-Poly1305 IETF
(RFC 8439)—, no formatos propietarios de libsodium. Bouncy Castle y libsodium producen
exactamente los mismos bytes para las mismas entradas.

**Cómo se verifica**: `docs/protocol.md` §7 fija vectores de prueba con claves fijas, y
**ambos** lados tienen un test que cifra y descifra esos mismos vectores y compara con las
mismas constantes hexadecimales. Si Kotlin y C# dejaran de entenderse, ese test se pone en
rojo sin necesidad de un móvil.

Se evita deliberadamente cualquier construcción específica de libsodium (`crypto_kx`, que
deriva con BLAKE2b, o `crypto_box` con su nonce de 24 bytes): la derivación de claves es
**HKDF-SHA256**, que existe igual en Bouncy Castle y en `System.Security.Cryptography.HKDF`.

---

## D-004 · Mensajes en JSON dentro del sobre cifrado

**Fecha**: 2026-08-14 · **Fase**: 0

El plan describe los mensajes con notación JSON pero no fija la codificación en el cable.
Se hace explícito: **el texto plano de cada mensaje cifrado es un objeto JSON UTF-8**.

- En C#: `System.Text.Json`, que viene con el runtime. Sin dependencia nueva.
- En Kotlin: `kotlinx-serialization-json` (**dependencia nueva**, se justifica aquí).

**Por qué JSON y no un codec binario propio**: el QR ya es JSON por decisión del plan
(§4.1), así que el lado Kotlin necesita un parser JSON de todas formas. Escribir uno a mano
para no añadir la dependencia sería cambiar una librería estándar y auditada por código
propio de parsing, que es justo donde se cometen errores. Y el volumen es irrelevante:
un clip de texto son unos cientos de bytes.

**Coste asumido**: el texto del clip viaja en base64 dentro del JSON (+33 %). Para
`text/plain` no importa. Cuando en v2 entren imágenes y ficheros, el campo `type` permitirá
enrutarlos a un frame binario sin tocar el resto del protocolo.

---

## D-005 · Serialización Kotlin sin plugin de compilador en el módulo JVM

**Fecha**: 2026-08-14 · **Fase**: 1

`kotlinx-serialization` se usa con su plugin de compilador (`@Serializable`). Se aplica solo
en `:protocolo`, que es JVM puro, para no meter el plugin en la compilación de `:app`.

---

## D-006 · Identificador de dispositivo y `origin_id`

**Fecha**: 2026-08-14 · **Fase**: 1

- `device_id`: 16 bytes aleatorios generados una vez por instalación, en hex. No deriva de
  ningún identificador de hardware (IMEI, Android ID, número de serie): sería un
  identificador persistente y rastreable, contrario al principio de privacidad del plan.
- `origin_id`: `SHA-256(contenido UTF-8)` truncado a 16 bytes, en hex. Es el mecanismo
  anti-eco del plan (§4.2). No es un secreto: viaja dentro del sobre cifrado y solo sirve
  para reconocer "este clip ya lo tengo".

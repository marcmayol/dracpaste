# DracPaste · guía de uso

Copiar en el PC y pegar en el móvil, y al revés. Sin nube, sin cuenta y sin que nada salga
de tu red local.

---

## Qué necesitas

- Un PC con **Windows 10 o 11**.
- Un móvil con **Android 10 o posterior**.
- Que los dos estén **en la misma red WiFi**. No hace falta internet: DracPaste no lo usa.

---

## Instalación

### En el PC

1. Descarga `DracPaste-1.0-instalador.exe` de la
   [última Release](https://github.com/marcmayol/dracpaste/releases/latest) y ejecútalo.
2. Windows mostrará un aviso azul de SmartScreen porque el instalador no está firmado con
   un certificado de pago. Pulsa **«Más información» → «Ejecutar de todas formas»**.
3. Marca «Arrancar DracPaste al iniciar sesión» si quieres que esté siempre listo.

No hace falta instalar .NET: el instalador trae todo lo necesario. Sí aparecerá **un aviso
de administrador**: es para dar permiso a DracPaste en el firewall de Windows. Acéptalo.

Si lo rechazas, la instalación sigue igual, pero el móvil no podrá conectarse: verá el PC
y la conexión morirá en un tiempo de espera. La app lo detecta al arrancar y te ofrece
arreglarlo desde su menú («Permitir en el firewall de Windows…»).

Al terminar verás un **icono verde en la bandeja del sistema**, junto al reloj. Ahí vive
DracPaste: no tiene ventana principal.

### En el móvil

La forma cómoda es desde **[DracApps](https://marcmayol.com/DracApps/)**, que instala y
mantiene al día todas las apps. Si prefieres el APK suelto, está en la
[última Release](https://github.com/marcmayol/dracpaste/releases/latest).

1. Instala el APK. Android pedirá permiso para instalar aplicaciones de esta procedencia:
   es lo normal al instalar fuera de Play Store.
2. Al abrir la app por primera vez, concede el permiso de **notificaciones**. No es un
   capricho: sin la notificación permanente, Android no permite mantener la conexión en
   segundo plano y DracPaste solo funcionaría con la app abierta.

**Se actualiza sola.** Cuando haya una versión nueva, la propia app te avisa con un aviso
arriba y se encarga del resto. Antes de instalar nada comprueba que el archivo descargado
es exactamente el que se publicó; si no cuadra, lo descarta.

---

## Emparejar

Solo se hace una vez por cada pareja de dispositivos.

1. **En el PC**: clic derecho en el icono de la bandeja → **«Emparejar un móvil…»**.
   Aparece un código QR con una cuenta atrás de dos minutos.
2. **En el móvil**: abre DracPaste → **«Escanear el código del PC»** → concede la cámara y
   apunta al QR.
3. En unos segundos, los dos mostrarán una **huella** como `A3F2-9C71`.

> **Comprueba que las dos huellas son iguales.** Es lo que confirma que estás hablando con
> tu PC y no con otra cosa de la red. Si no coinciden, no sigas.

La notificación del móvil pasará a **«Conectado con [tu PC]»**.

### Si la cámara no funciona

En el PC, pulsa **«Copiar el texto»** y pásate ese texto al móvil como prefieras. En el
móvil, toca **«La cámara no funciona: pegar el texto»** y pégalo.

---

## Usar

### Del PC al móvil: automático

Copia lo que sea en el PC. Ya está en el portapapeles del móvil, listo para pegar. No hay
que hacer nada más.

### Del móvil al PC: un gesto

Aquí hay dos formas, y las dos necesitan que hagas algo. **No es un defecto de DracPaste**:
desde Android 10, una aplicación no puede leer tu portapapeles a no ser que esté en
pantalla o sea tu teclado. DracPaste no hace ninguna de las dos cosas, y no va a empezar.

**Opción A · el botón de la notificación.** Copia algo y toca **«Enviar portapapeles»** en
la notificación de DracPaste. Se abre una ventana invisible una décima de segundo, lee y se
cierra. No verás nada.

**Opción B · compartir.** Selecciona un texto en cualquier app, usa **«Compartir»** y elige
**«Enviar al PC»**. Esta vía ni siquiera toca el portapapeles: lo que tuvieras copiado se
queda como estaba.

---

## Que no se muera en segundo plano

Es lo que más problemas da, y no depende de DracPaste sino del fabricante del móvil.

1. En DracPaste: **Ajustes y batería → «Quitar las restricciones»**.
2. Si tienes un **Xiaomi, Samsung, Huawei, OPPO o OnePlus**, además hay pasos propios de la
   marca: la app los detecta y te los enseña en esa misma pantalla.

Si un día copias algo en el PC y no llega al móvil, pero sí llega en cuanto desbloqueas la
pantalla, es exactamente esto.

---

## Privacidad: qué hace y qué no

**Lo que hace:**

- Habla directamente con tu PC por la red local, cifrado de extremo a extremo (X25519 y
  ChaCha20-Poly1305), con claves nuevas en cada conexión.
- Guarda solo las claves públicas. La privada del móvil está envuelta por el hardware
  seguro del propio dispositivo; la del PC, cifrada con tu cuenta de Windows.

**Lo que no hace, y no es por descuido:**

- **No sincroniza lo que copies desde un gestor de contraseñas.** Cuando Bitwarden,
  1Password o el de Google copian algo, lo marcan como sensible; DracPaste lo respeta y te
  avisa de que no lo ha enviado. No hay forma de desactivarlo.
- **No guarda historial.** Lo que copias no queda escrito en ningún sitio.
- **No sale de tu red.** No hay servidores, ni relay, ni cuenta. Si el PC y el móvil no
  están en la misma red, no hay sincronización, y la app te lo dice en vez de improvisar.
- **No recoge estadísticas.** No hay nada que enviar porque no hay a dónde enviarlo.
- **No pide permisos que no use.** Ni almacenamiento, ni contactos, ni ubicación. La cámara
  solo para escanear el QR, y es opcional.

---

## Cuando algo no va

**«El PC no responde» al emparejar, pero sé que estamos en la misma red**

Es lo más habitual, y casi siempre es **el firewall de Windows**. Los dos dispositivos se
ven —el ping funciona— pero el firewall se traga la conexión y desde el móvil eso parece un
problema de red.

En el PC: menú de DracPaste → **«Permitir en el firewall de Windows…»** y acepta el aviso
de administrador. Si esa opción no aparece en el menú, es que las reglas ya están puestas y
el problema es otro.

**«Sin emparejar» y no encuentro el PC**
Comprueba que los dos están en el mismo WiFi. Algunas redes —hoteles, oficinas, algunos
routers con «aislamiento de clientes»— impiden que dos dispositivos se vean entre ellos: en
esas redes DracPaste no puede funcionar.

**Copio en el PC y no llega**
Mira la notificación del móvil. Si dice «Conectado», mira si tienes la sincronización
pausada en los ajustes. Si dice «Reconectando», espera unos segundos: reintenta solo.

**Llega, pero tarda**
La primera vez después de un rato largo puede tardar unos segundos en despertar. Si tarda
siempre, es la batería: mira la sección de arriba.

**Copio una contraseña y no se envía**
Es a propósito. Ver «Privacidad».

**Un texto muy largo no se envía**
El máximo son 256 KB, que son unas 100 páginas de texto. La app avisa cuando pasa de ahí.

**He cambiado de móvil**
Empareja el nuevo y desempareja el viejo desde los ajustes del PC. Cada pareja tiene su
propia clave: quitar una no afecta a las demás.

---

## Desinstalar

**En el PC**: Configuración → Aplicaciones → DracPaste → Desinstalar. Preguntará si quieres
borrar también las claves de emparejamiento. Si dices que no y vuelves a instalarlo, tus
móviles seguirán emparejados.

**En el móvil**: como cualquier otra app. Al desinstalarla, la clave privada desaparece con
ella: el hardware seguro del móvil la destruye.

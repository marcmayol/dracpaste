# Encargo de pantallas para DracPaste

Segundo encargo para Claude Design, después del de identidad ([brief-branding.md](brief-branding.md)).
El logo ya está decidido; esto es la interfaz.

Todo lo que hay aquí está sacado del código que ya funciona: los textos son literales y las
medidas son las de verdad. Nada es hipotético.

**Hay capturas reales en [capturas/](capturas/)**, sacadas de mi Pixel y de mi PC:
`android-principal.png`, `android-ajustes.png`, `android-notificacion.png`,
`windows-emparejamiento.png` y `windows-ajustes.png`.

Dos avisos sobre ellas: en la de ajustes de Windows pone «Escuchando en el puerto 0» porque
la ventana se abrió con un servidor de mentira para poder capturarla —en la app real ahí
sale 47653—, y los colores de los textos en las dos capturas de Windows están alterados por
el método de captura. Fíate del texto de este documento para los colores, no de los PNG.

---

Ya tengo la identidad de **DracPaste** y ahora necesito el diseño de sus pantallas. La app
está **terminada y publicada**, así que esto es un rediseño sobre algo que funciona, no una
propuesta en el aire: cada pantalla que te describo existe, tiene esos textos y esas
medidas, y hay gente usándola.

Te paso primero lo único que de verdad tienes que entender para diseñar esto bien.

## El problema de diseño

DracPaste sincroniza el portapapeles entre un PC con Windows y un móvil Android. Pero **las
dos direcciones no funcionan igual**, y no por capricho:

- **PC → móvil es automático.** Copias en el PC y aparece en el móvil. Sin tocar nada.
- **Móvil → PC necesita un gesto.** Desde Android 10, una app **no puede leer el
  portapapeles si no tiene el foco de la pantalla**. Es una protección del sistema y no hay
  forma de saltársela. Así que el usuario tiene que desplegar la notificación y tocar
  «Enviar portapapeles», o compartir el texto a DracPaste desde el menú de compartir.

**Esta asimetría es el problema central del diseño, y ya ha fallado en la vida real**: yo
mismo, que hice la app, copié algo en el móvil y me quedé esperando a que llegara al PC.
Si me pasa a mí, le pasa a cualquiera.

Lo parcheé cambiando el texto de la notificación a «Lo del PC llega solo · para enviar lo
tuyo, despliega y toca Enviar», pero eso es una tirita. **Quiero que el diseño lo resuelva
de verdad**: que se vea, sin leer, que una dirección fluye sola y la otra pide un dedo. Es
lo primero que quiero ver resuelto en tus propuestas.

Lo que **no** quiero es que lo resuelvas mintiendo. Nada de dibujar una flecha
bidireccional bonita que dé a entender que las dos van solas.

## Restricciones técnicas que no son negociables

Léelas antes de dibujar, porque descartan cosas.

**El PC es WinForms** (.NET 8), no WPF ni web. Los controles disponibles son `Label`,
`Button`, `CheckBox`, `ListView`, `PictureBox`, `TextBox`, `FlowLayoutPanel` y `Panel`, con
posicionado por `Dock`. **No hay** esquinas redondeadas, sombras, animaciones ni gradientes
salvo que se dibujen a mano con GDI+. Si una propuesta tuya necesita pintado propio
(`owner-draw`), dilo explícitamente y márcalo, porque eso es trabajo real y quiero decidir
si lo pago.

**WinForms no sigue el modo oscuro de Windows.** Hoy la app es blanca siempre. Dime qué
hacemos: o asumimos claro y lo hacemos bien, o pintamos el oscuro a mano y entonces
necesito los colores de los dos.

**Escalado DPI**: mis pantallas van al 125 % y 150 %. Los tamaños fijos en píxeles que te
doy abajo se multiplican. Si tu diseño depende de que algo quepa justo, se va a romper.

**El móvil es Jetpack Compose con Material 3.** Decide y justifica una cosa: si respetamos
el **color dinámico** de Android 12+ (la app se tiñe con el fondo de pantalla del usuario)
o si imponemos la paleta de la marca. Las dos son defendibles; quiero tu criterio.

**Tamaño de letra grande**: pruebo siempre con la escala de fuente del sistema al 130 % y
al 150 %. Los diseños con alturas fijas y texto dentro se rompen ahí. Enséñame al menos las
pantallas más apretadas al 150 %.

**Sin onboarding.** No hay pantalla de bienvenida y no quiero añadirla.

---

# Pantallas del móvil (Android)

## 1. Pantalla principal

**Empieza por un fallo que se ve en la captura**: el título «DracPaste» está pisando el
reloj de la barra de estado. La app dibuja de borde a borde sin respetar los márgenes del
sistema, y pasa igual en la pantalla de ajustes con el título «Ajustes». Hay que resolverlo,
y de paso decidir qué hacemos con esa zona: hoy es espacio desperdiciado.

Es una sola columna con desplazamiento vertical, 24 dp de margen, 16 dp entre bloques. De
arriba abajo:

- Título «DracPaste» y bajada «Tu portapapeles compartido con el PC, sin nube y sin cuenta.»
- Un banner de actualización, que casi siempre está oculto (ver punto 5).
- **La lista de PCs emparejados**, o el vacío.
- Un separador, y la sección de emparejar.
- Un separador, el acceso a «Ajustes y batería», y un pie legal.

**Estado vacío** (nadie ha emparejado nada todavía): hoy pone escuetamente «Ningún PC
emparejado». Es la primera pantalla que ve alguien y está desaprovechada: aquí es donde hay
que explicar de un vistazo qué hace la app y qué tiene que hacer la persona.

**Con un PC emparejado**: una tarjeta por PC con el nombre, la huella en monoespaciada
(«Huella A1B2 C3D4»), y o bien la etiqueta «Destino activo» o bien el botón «Usar este
PC», más «Desemparejar».

**Con varios PCs**: aparece además «Los clips van solo al PC activo.» Hace falta que se vea
sin leer **cuál es el activo**, porque es el único al que van las cosas.

La sección de emparejar dice: «En el PC, abre DracPaste desde la bandeja y pulsa
«Emparejar un móvil». Después, apunta con la cámara al código que aparece.», con el botón
principal «Escanear el código del PC» y debajo un enlace «La cámara no funciona: pegar el
texto».

El pie dice: «DracPaste no envía nada fuera de tu red local, no guarda historial y nunca
sincroniza lo que copies desde un gestor de contraseñas.» Es el argumento de venta de la
app entero, y hoy está en letra pequeña al final de todo, que es donde no lo lee nadie.
Dime dónde debería estar.

## 2. Escaneo del QR

Título «Apunta al código del PC», un recuadro cuadrado (relación 1:1) con las esquinas
redondeadas a 16 dp y **la cámara a pantalla completa dentro, sobre fondo negro**, y debajo
un botón «Cancelar». No hay guía visual de puntería: es un rectángulo negro con la imagen
de la cámara. Falta el marco o las esquinas que le digan a la persona dónde poner el
código.

## 3. Emparejar pegando el texto (plan B)

Cuando la cámara falla o no hay permiso, se despliega un campo de texto de 3 a 6 líneas
etiquetado «Texto del PC» y un botón «Emparejar con este texto». Lo que se pega es un JSON
de unos 250 caracteres. Hoy es un `OutlinedTextField` normal y parece un formulario
cualquiera, cuando en realidad es un pegado a ciegas de algo ilegible.

## 4. Confirmación de huella (crítica)

Al emparejar sale una tarjeta con este texto:

> Emparejado con MARC.
>
> Comprueba que el PC muestra esta misma huella: A1B2 C3D4

y un botón «Entendido».

**Esto es lo más importante que hay en toda la app** y hoy tiene el mismo aspecto que un
aviso cualquiera. La huella es lo único que impide que alguien se cuele en medio del
emparejamiento; si el usuario no la compara con la del PC, la garantía se pierde. Necesito
un tratamiento que empuje a mirar las dos pantallas a la vez, sin dar miedo y sin ser un
trámite que se despacha a ciegas.

## 5. Banner de actualización

DracPaste se actualiza sola desde mi propia tienda, sin Play Store. El banner aparece
arriba cuando hay versión nueva, con el número, las notas y un botón para actualizar.
Estados: hay actualización / descargando (con progreso) / lista para instalar / error.

## 6. Diálogo de desemparejar

Título «¿Desemparejar MARC?», cuerpo:

> Este móvil borrará su clave y dejará de conectarse. Si el PC está encendido, borrará la
> suya también.
>
> Para volver a usarlo habrá que emparejarlo de nuevo con un código.

Botones «Desemparejar» y «Cancelar».

## 7. Ajustes

Columna con 24 dp de margen. Contiene, en este orden:

**Dos interruptores**: «Pausar la sincronización» («Mientras esté pausada, no sale ni entra
nada. La conexión con el PC se mantiene.») y «Avisar al recibir un clip».

**Un bloque sobre la batería**, que es una tarjeta con dos caras:
- Bien: «Batería: sin restricciones ✓» / «Android no matará la conexión por ahorro de batería.»
- Mal: «Batería: con restricciones» + una explicación larga + el botón «Quitar las restricciones».
- Y en móviles de ciertos fabricantes, un aviso extra de que la marca tiene *además* su
  propio matador de apps, con pasos numerados desplegables.

**Cinco datos que no son interruptores**, bajo el título «Cómo funciona»: «Solo red local»,
«Cifrado de extremo a extremo», «Clips sensibles» («Nunca se sincronizan. No hay forma de
activarlo, y es a propósito.»), «Sin historial», «Sin analíticas».

Ese último bloque **son afirmaciones, no opciones**, y hoy se parecen demasiado a los
interruptores de arriba: se leen como ajustes apagados. La captura lo deja claro — tienen
el mismo título en negrita y la misma explicación debajo, solo que sin interruptor a la
derecha. Hay que distinguirlos. Es una decisión de producto que quiero que se vea: la app
te enseña sus reglas y no te deja romperlas.

Dos cosas más de esta pantalla: **para volver hay que bajar hasta el final del todo**, donde
hay un enlace «Volver» perdido bajo el último párrafo (no hay barra superior ni flecha
atrás); y el bloque de batería es un muro de texto en el estado malo, que es justo cuando
hay que convencer a alguien de tocar un ajuste del sistema.

Nota sobre las capturas del móvil: están en **tema oscuro y con el color dinámico de
Android activo**, de ahí ese verde menta. No es una decisión de marca: es el fondo de
pantalla de mi móvil tiñendo la app. Es exactamente la decisión que te pido que tomes más
arriba.

---

# La notificación de Android

Es lo que más se ve de la app: es **permanente** mientras está conectada, así que vive en
la barra de estado todo el día. Muchos usuarios no abrirán nunca la pantalla principal.

Estados del título:

| Estado | Título |
|---|---|
| Sin emparejar | `Sin emparejar` |
| Buscando | `Buscando tu PC` |
| Conectando | `Conectando con MARC` |
| Conectado | `Conectado con MARC` |
| Reconectando | `Reconectando con MARC` |
| En pausa | `Sincronización en pausa` |
| Clip recibido sin poder pegarlo | `Clip recibido, toca para pegarlo` |

Cuerpo actual cuando está conectada: «Lo del PC llega solo · para enviar lo tuyo, despliega
y toca Enviar».

Botones: **«Enviar portapapeles»** (siempre que haya un PC activo) y **«Pegar»** (solo
cuando Android no ha dejado escribir en el portapapeles en segundo plano).

**Mira `android-notificacion.png` antes de proponer nada**, porque enseña dos cosas que no
se deducen del código:

**Está en el cajón de «Silenciadas»**, mezclada con la publicidad de Amazon y con «Cargando
por USB». Es consecuencia de haberla puesto en prioridad baja y sin sonido, que era lo
correcto —nadie quiere que su portapapeles pite—, pero el precio es que queda enterrada
justo debajo de la basura. Si el diseño depende de que la persona vea esta notificación,
hay que contar con dónde acaba de verdad.

**Colapsada, el texto se corta exactamente donde está la instrucción.** Se lee «Lo del PC
llega solo · para enviar lo tuyo,…» y ahí muere: la parte que dice qué hacer («despliega y
toca Enviar») es la que no se ve. Puse un `BigTextStyle` para que al desplegarla se lea
entera, pero eso solo funciona **si la persona ya sabe que tiene que desplegarla**, que es
precisamente lo que no sabe. Lo importante tiene que caber en la primera línea, y en esa
primera línea caben unos 40 caracteres.

Necesito:

- **Colapsada y desplegada**, para cada estado. Colapsada solo se ve una línea y **los
  botones no aparecen**: el botón de enviar solo existe al desplegar, y eso es justo lo que
  el usuario no sabe que tiene que hacer.
- Que **conectado, reconectando y sin conexión se distingan de un vistazo**, contando con
  que el icono de la barra de estado es monocromo y diminuto.
- Tu propuesta de microcopy. Puedes reescribir los textos, pero **sin prometer que la
  sincronización va sola en las dos direcciones**, porque no es verdad.

---

# Pantallas del PC (Windows)

La app **no tiene ventana principal**: vive en la bandeja del sistema y las ventanas se
abren desde su menú.

## 1. Menú de la bandeja

Al pulsar el icono sale un menú con: la línea de estado (desactivada, solo informativa), un
aviso de firewall que casi siempre está oculto, «Emparejar un móvil…», «Ajustes…» y
«Salir».

Los estados que puede mostrar esa primera línea: `Arrancando…`, `Sin emparejar`, `Esperando
al móvil`, `Conectado con Pixel 8 Pro`, `En pausa`, y `Sin móvil conectado · el último clip
no se envió`.

El **tooltip del icono** es `DracPaste · <estado>` y **Windows lo corta a 63 caracteres**,
así que el estado tiene que ser corto de verdad.

## 2. Ventana de emparejamiento — 420 × 620 px, fija, no redimensionable

De arriba abajo, con las alturas reales de hoy:

```
+------------------------------------------+  420 px de ancho
| Abre DracPaste en el móvil, pulsa        |
| «Emparejar un PC» y apunta con la        |  88 px
| cámara a este código.                    |
| Este PC: MARC · 192.168.1.42:47653       |
+------------------------------------------+
|                                          |
|          [ el código QR ]                |  320 px
|                                          |
+------------------------------------------+
|          Huella: A1B2 C3D4               |  32 px, monoespaciada, negrita
|  El móvil debe mostrar esta misma huella |  28 px, gris
|            Caduca en 4:37                |  24 px, gris (cuenta atrás en vivo)
+------------------------------------------+
| Si la cámara no lo lee, pega este texto: |  22 px, gris
| +--------------------------------------+ |
| | {"pk":"...","ip":"...","token":"..."}| |  rellena lo que queda
| +--------------------------------------+ |  monoespaciada 8pt, gris claro
+------------------------------------------+
|            [Copiar el texto]  [Cerrar]   |  52 px
+------------------------------------------+
```

El código **caduca a los 2 minutos** y la cuenta atrás va en vivo. Al llegar a cero, el
texto se pone rojo: «El código ha caducado. Cierra y vuelve a abrir esta ventana.»

Dos minutos es poquísimo, y esto es un problema de diseño de verdad, no un detalle: en ese
tiempo la persona tiene que coger el móvil, abrir DracPaste, encontrar el botón de
escanear, conceder el permiso de la cámara si es la primera vez, y apuntar. A mí se me
caducó haciendo justo eso. Como la ventana enseña una cuenta atrás discreta y gris en
mitad de la pantalla, **un código muerto se parece muchísimo a uno vivo**.

Necesito que el paso de «válido» a «caducado» sea imposible de no ver, y que el estado
caducado ofrezca la salida (volver a generar) en lugar de mandar a cerrar y reabrir la
ventana a mano, que es lo que hace hoy.

Ojo con una cosa: al abrir la ventana **la huella todavía no se conoce** (depende de la
clave del móvil, que llega al emparejar), así que en vez del valor pone «Comprueba la
huella al terminar». Hay dos momentos distintos y hoy se resuelven cambiando un texto.

## 3. Ventana de ajustes — 560 × 420 px, fija

- Cabecera: «Este PC: MARC» / «Escuchando en el puerto 47653 · solo red local · cifrado de
  extremo a extremo».
- Una tabla (`ListView`) con columnas **Móvil (180 px), Huella (100), Emparejado (130),
  Estado (98)** — suman 508, que es lo que cabe. Estado es `Conectado` o `—`.
- Vacía pone «Ningún móvil emparejado todavía» en gris.
- Dos casillas: «Arrancar DracPaste al iniciar sesión en Windows» y «Pausar la
  sincronización (nada sale ni entra)».
- Un pie gris: «DracPaste no envía nada fuera de tu red local, no guarda historial de clips
  y no recoge ninguna estadística.»
- Botones «Desemparejar» y «Cerrar».

## 4. Globos de la bandeja

- Al emparejar: «Emparejado con Pixel 8 Pro · huella A1B2 C3D4».
- Texto demasiado grande: «Ese texto ocupa demasiado (120.000 caracteres) y no se ha
  enviado al móvil.»
- **Aviso de firewall**, el más importante: «El firewall de Windows va a bloquear al móvil.
  Abre el menú de DracPaste y pulsa «Permitir en el firewall de Windows…».»

Ese último merece atención especial. Sin la regla del firewall, **el móvil ve el PC pero la
conexión muere en un tiempo de espera**, y desde el móvil eso parece un problema de red: es
el fallo que más tiempo me hizo perder a mí construyendo esto. El globo se lo come
cualquiera. Dime si esto debería ser algo más que un globo.

---

# Qué espero de vuelta

1. **Primero, y antes de dibujar ninguna pantalla completa**: cómo resuelves la asimetría
   entre las dos direcciones. Si eso no está resuelto, lo demás da igual.
2. Mockups de **cada pantalla en todos sus estados**, incluidos los vacíos y los de error
   (no solo el caso feliz, que es el que nunca se ve).
3. La **notificación colapsada y desplegada** en cada estado.
4. **Espaciados, tamaños y colores concretos**, en dp para Compose y en píxeles para
   WinForms, teniendo en cuenta que WinForms escala.
5. Qué propuestas necesitan **pintado a mano en WinForms** y cuáles salen con los controles
   de serie.
6. Las pantallas más apretadas **con el texto al 150 %**.

Si algún texto mío está mal escrito o es confuso, cámbialo y dime por qué. Lo único que no
se puede tocar es la honestidad de lo que promete: esta app se vende con que no manda nada
a ninguna parte, y la interfaz no puede sugerir capacidades que no tiene.

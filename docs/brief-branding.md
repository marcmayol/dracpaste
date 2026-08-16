# Encargo de identidad para DracPaste

Este es el texto que se le pasa a Claude Design. Se guarda aquí para poder repetir el
encargo o pedir variantes sin volver a redactarlo.

---

Necesito la identidad visual completa de **DracPaste**, una aplicación que ya está
terminada y publicada. No es un concepto: hay código funcionando, así que la identidad
tiene que caber en sitios muy concretos que te detallo abajo.

## Qué es

Un **portapapeles compartido entre un móvil Android y un PC con Windows**. Copias algo en
el PC y aparece en el móvil; en el móvil pulsas un botón y llega al PC. Nada más.

Lo que la distingue de las alternativas es de dónde **no** pasa la información:

- **Solo por la red local.** No hay servidor, no hay nube, no sale de casa.
- **Cifrado de extremo a extremo** (X25519 + ChaCha20-Poly1305). Ni siquiera la LAN ve el
  contenido.
- **Sin cuentas.** No hay registro, ni correo, ni contraseña.
- **Sin telemetría.** Cero.
- Se emparejan escaneando un **código QR** que muestra el PC. Una vez, y ya.

El sentimiento que debe transmitir es **confianza tranquila y doméstica**: dos aparatos
míos que se hablan entre ellos en mi casa, y de los que nadie más se entera. No quiero
estética de "producto de seguridad" —ni candados, ni escudos, ni verde hacker, ni fondos
negros con líneas de neón—; eso vende miedo, y aquí la sensación es la contraria: alivio de
que la cosa sea pequeña y no llame a nadie.

## Familia a la que pertenece

Forma parte de un conjunto de aplicaciones propias distribuidas fuera de Play Store desde
una tienda propia, **DracApps**. El prefijo "Drac" (dragón, en catalán) es la marca común.
Ya existe **DracPDF**, cuya identidad gira en torno a un dragón llamado Ladón.

Quiero que **se reconozca como de la misma familia sin ser una copia**: mismo aire, misma
manera de dibujar, pero con personalidad propia. Dime explícitamente qué rasgo es el que
hace de "apellido" compartido (el trazo, la paleta, la geometría, el gesto...), porque ese
rasgo lo voy a reutilizar en las siguientes.

Referencia del tono general de mi trabajo: mi web personal es neo-brutalista editorial
(tipografías Archivo y Space Mono, rojo #d62828, bordes duros, nada de sombras suaves). No
tienes que ceñirte a eso, pero si te alejas, que sea a propósito y me lo expliques.

## Qué necesito exactamente

Esto es lo importante del encargo: los iconos tienen que **funcionar a tamaños diminutos y
en un solo color**, porque el sitio donde más se va a ver la marca es una bandeja del
sistema a 16 píxeles. Un dibujo bonito que a 16 px se convierte en una mancha no me sirve.

**Marca**

1. **Wordmark "DracPaste"** y una versión corta para espacios estrechos.
2. **Símbolo** que funcione suelto, sin el texto.

**Android**

3. **Icono adaptativo**: capas de fondo y frente por separado, lienzo de 108 dp con la zona
   segura de 66 dp respetada (Android recorta en círculo, cuadrado redondeado y otras
   formas según el lanzador).
4. **Versión monocroma** de una sola capa para el tema dinámico de Android 13+, donde el
   sistema la recolorea con los colores del fondo de pantalla del usuario.
5. **Icono de notificación**: silueta blanca sobre transparente, 24 dp. Android descarta
   todo el color aquí. La app tiene una **notificación permanente** mientras está
   conectada, así que este icono va a estar en la barra de estado todo el día: tiene que
   ser reconocible y no molestar.

**Windows**

6. **Icono de bandeja** monocromo a 16, 20, 24 y 32 px, legible **tanto en barra de tareas
   clara como oscura** (haz las dos versiones y enséñamelas sobre los dos fondos reales).
7. **.ico multirresolución** (16, 32, 48, 256) para el ejecutable y el instalador.
8. Un **estado visual distinto para "sin conexión"** del mismo icono de bandeja: el usuario
   tiene que poder saber de un vistazo si está conectado o no, **sin depender del color**
   (a 16 px y en monocromo el color no está disponible).

**Distribución**

9. **Imagen del asistente de instalación** (Inno Setup): 164×314 px la grande, 55×58 px la
   pequeña.
10. **Tarjeta para el catálogo de DracApps** y una imagen ancha de cabecera para la página
    de descarga.

**Sistema**

11. **Paleta con tokens** (nombre, valor, y para qué sirve cada uno), con **modo claro y
    oscuro**, y contraste de texto que cumpla WCAG AA.
12. **Tipografías**, con alternativas libres si la principal no lo es.
13. Cómo dibujar los dos estados que la app enseña constantemente: **conectado** y
    **reconectando**.

## Restricciones que no puedo saltarme

- La app Android es **Material 3 con Jetpack Compose**, y el PC es **WinForms**. Los tokens
  de color me tienen que servir para los dos; no me des un sistema que solo tenga sentido
  en web.
- El icono monocromo de Android y el de bandeja de Windows **no pueden depender del color
  para significar nada**.
- Todo tiene que ser **vectorial de origen** (SVG), porque el icono de notificación acaba
  siendo un VectorDrawable de Android.
- La app no tiene ninguna pantalla de bienvenida ni onboarding, y no quiero añadirla. La
  identidad tiene que sostenerse en el icono, la notificación y la pantalla única de
  emparejamiento.

## Qué espero de vuelta

Enséñame **dos o tres direcciones distintas** antes de desarrollar ninguna, y para cada una
enséñame **el símbolo a 16 px en la bandeja de Windows desde el primer momento** —no al
final—, porque ese es el tamaño que va a matar las propuestas que no aguanten. Cuando elija
una, desarrolla el conjunto completo.

Y dime en qué se diferencia visualmente de DracPDF, para que no acabemos con dos dragones
que se confunden en la misma pantalla del móvil.

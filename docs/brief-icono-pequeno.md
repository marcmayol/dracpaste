# Encargo de seguimiento: la cabeza de Ladón a tamaño pequeño

Para pasarle a Claude Design después de la entrega de identidad y pantallas.

---

La cabeza de Ladón me gusta y para tamaño grande está resuelta. Pero **a tamaño de icono
no funciona**, y lo he comprobado antes de pedirte nada: he reducido
`assets/drac-head.png` a los tamaños reales de la bandeja de Windows y esto es lo que sale.

- **A 16 px es una mancha gris.** No se distingue ni el morro ni los cuernos: la melena y
  las púas se funden en un borrón.
- **A 20 px, igual.**
- **A 24 px** empieza a adivinarse.
- **A 32 px** ya se reconoce el dragón.

El problema es que 16 px es justo donde más se va a ver la marca —la bandeja del sistema—,
y el icono de notificación de Android se dibuja a 24 dp en silueta plana. El diseño actual
tiene demasiados elementos finos: la melena en capas, los dos cuernos, la línea de la
mandíbula, el ojo hueco. A esos tamaños solo sobrevive lo que ocupa 2 o 3 píxeles de grosor.

## Lo que necesito

**1. Una silueta reducida** de la misma cabeza, pensada desde cero para 16-24 px. No un
reescalado: un dibujo distinto que se lea como el mismo dragón. Mi apuesta es que sobreviven
el perfil del morro y **un** cuerno, y que la melena hay que sacrificarla entera — pero es
tu decisión, no la mía.

Enséñamela **directamente a 16 px sobre barra de tareas clara y oscura** antes de
enseñármela grande. Si a ese tamaño no se lee, no vale, por bonita que sea a 128.

**2. Las dos versiones tienen que reconocerse como el mismo animal.** Alguien que ve el
icono de la bandeja y luego abre la app tiene que entender que es la misma marca. Dime qué
rasgo es el que mantiene el parecido.

**3. El SVG que falta.** Todo lo entregado es PNG, y en el README lo dejas como pendiente.
Sin vector no puedo generar nada de esto:

- El **VectorDrawable** del icono de notificación de Android (24 dp, silueta blanca sobre
  transparente, plana, sin degradados).
- El **icono adaptativo** de Android: capas de fondo y frente, lienzo 108 dp con la zona
  segura de 66 dp respetada.
- La **versión monocroma** de una capa para el tema dinámico de Android 13+.
- El **.ico multirresolución** de Windows: 16, 32, 48 y 256, donde el de 16 y el de 32
  deberían usar la silueta reducida y los grandes la cabeza completa.

**4. Las cuatro variantes de estado de la bandeja**, que ya definiste en el handoff, pero
aplicadas a la silueta nueva: conectado (sólida), reconectando (al 38 %), sin móvil
(atenuada con una «✕»), y en pausa (sólida con un cuadradito hueco). A 16 px y en
monocromo, **la diferencia tiene que verse por forma, no por color ni por opacidad sola**:
un 38 % de opacidad a 16 píxeles puede ser indistinguible de un icono normal en una pantalla
con brillo bajo.

Todo lo demás del handoff está bien y ya lo estoy implementando; esto es lo único que me
bloquea para generar los iconos.

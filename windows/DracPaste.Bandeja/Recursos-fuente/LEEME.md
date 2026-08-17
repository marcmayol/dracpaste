# De dónde salen los iconos

Todos se generan a partir de un único dibujo: `design-handoff/assets/drac-head.png`, la
cabeza de Ladón que entregó Claude Design.

- `generar-iconos.ps1` — los dos PNG de Android: el frente del icono adaptativo
  (`ic_launcher_foreground.png`, con la zona segura de 66 dp respetada) y el de
  notificación (`ic_notificacion.png`, silueta blanca que Android tiñe).
- `Ico.cs` — el `dracpaste.ico` de Windows, con 16, 24, 32, 48, 64, 128 y 256.

## Por qué el .ico se genera con C# y no con el mismo script

Se intentó, y salió mal de una forma que conviene no repetir: las imágenes dentro de un
`.ico` **tienen que ir como DIB, no como PNG**. El formato admite PNG desde Vista, pero
GDI+ —que es quien lee el icono en WinForms— los interpreta como DIB de todas formas, y
en pantalla aparece ruido de colores en vez del dibujo. Con un `.ico` así, la app arranca
sin quejarse: el fallo solo se ve mirando la bandeja.

## Lo que este dibujo todavía no resuelve

A 16 px la cabeza completa es una mancha: la melena en capas, los dos cuernos y la línea
de la mandíbula no sobreviven a ese tamaño. Hace falta una **silueta simplificada**
dibujada para 16-24 px, y está pedida en `docs/brief-icono-pequeno.md`. Cuando llegue,
sustituye al origen de aquí y estos dos generadores siguen valiendo igual.

Tampoco hay SVG: todo lo entregado es PNG. Sin vector no se puede hacer un VectorDrawable
de notificación de verdad, y el que hay es un PNG a 96 px que Android aplana.

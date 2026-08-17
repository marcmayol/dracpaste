# Handoff: DracPaste — identidad + pantallas (WinForms y Android/Compose)

## Qué es esto
Paquete de implementación del rediseño de **DracPaste** (portapapeles compartido PC⇄móvil por red local, cifrado e2e, sin cuentas ni telemetría). Va dirigido a Claude Code trabajando sobre **dos codebases existentes**:
- **PC**: WinForms, .NET 8 (controles de serie; owner-draw solo donde se marca `GDI+`).
- **Móvil**: Kotlin + Jetpack Compose, Material 3.

**Los HTML de este paquete son referencias de diseño, no código de producción.** Hay que recrear lo que muestran usando los patrones de cada codebase. Fidelidad: **alta (hifi)** — colores, textos, espaciados y jerarquías son finales; los mocks de Windows usan las medidas reales de las ventanas.

## Archivos
- `DracPaste Pantallas.dc.html` — TODAS las pantallas y estados (fuente de verdad). Secciones: 3 modelo/decisiones, 4 principal Android, 5 emparejamiento Android, 6 ajustes+notificación, 7 Windows, 8 tokens.
- `DracPaste Identidad.dc.html` — logo, lockups, usos.
- `DracPaste Direcciones.dc.html` — exploración (contexto, no implementar).
- `assets/logo-dracpaste.png` — **el logo completo** (hoja doblada + Ladón + cursor), 880×1020. Úsalo en Acerca de, instalador y catálogo. A tamaños de bandeja/notificación NO: ahí va solo la cabeza.
- `assets/drac-head.png` — cabeza de Ladón, fondo transparente: la versión para iconos pequeños. **Pendiente: vectorizarla** antes de generar el VectorDrawable de notificación y el .ico.
- `encargos/` — los dos briefs originales del autor (contexto y textos literales).

## El principio de diseño que gobierna todo
**La asimetría no se esconde, se dibuja.** PC→móvil es automático; móvil→PC exige un gesto (restricción de Android 10+: sin foco no se lee el portapapeles).
- Dirección automática: línea continua con flecha; se narra en pasiva («llega solo»).
- Dirección manual: línea discontinua que se completa **atravesando un botón**; siempre imperativa («toca Enviar»).
- **Prohibido**: flechas bidireccionales o copy que prometa sincronización automática en ambos sentidos.

## Decisiones cerradas
1. **Sin color dinámico (Material You) dentro de la app.** Paleta de marca en claro y oscuro (tabla abajo). El icono monocromo del launcher sí participa en Material You.
2. **WinForms solo en claro**, bien hecho. Nada de modo oscuro pintado a mano.
3. **Nuevo botón primario en la pantalla principal Android: «Enviar lo copiado al PC»** — con la app en primer plano hay foco y la lectura del portapapeles es legal. Es LA pieza que resuelve la asimetría.

## Tokens (Compose y WinForms)
| token | claro | oscuro (solo Android) | uso |
|---|---|---|---|
| ink | #1a1a1a | #ece5dc | texto y bordes principales |
| paper | #faf8f4 | #171310 | fondo de pantalla |
| card | #ffffff | #211c17 | tarjetas / ventanas |
| muted | #5c554e | #a89f93 | texto secundario (AA: 6,1:1 / 7,3:1 sobre paper) |
| accent | #c2521e | #e07a4a | SOLO acciones (enviar, escanear, actualizar). Nunca significa estado. |
| danger | #8c2f10 | #f0996e | destructivo y avisos (batería, firewall, caducidad) |
| line | #ddd6cd | #3a332c | separadores, bordes pasivos |

- Texto sobre accent: blanco en claro (#fff sobre #c2521e = 4,6:1), **ink** sobre #e07a4a en oscuro.
- Espaciado: base 4. Márgenes de pantalla 24 dp; entre bloques 16 dp; interior de tarjeta 16/12 dp.
- Radios Android: tarjetas 16 dp, botones píldora (99). Windows: 0, todo recto.
- Tipos Android: Roboto (UI) + **Roboto Mono para toda huella** (regla dura). Windows: Segoe UI + Consolas. Archivo Black solo para el wordmark «DRACPASTE».
- Estados NUNCA por color solo (icono bandeja y notificación son monocromos).

---

# ANDROID (Kotlin + Compose, Material 3)

## A1. Principal — vacía (`#4a` del HTML)
- Respetar insets del sistema (`WindowInsets.safeDrawing`): hoy el título pisa el reloj — **bug a corregir** también en Ajustes.
- Columna scroll, margen 24 dp, 16 dp entre bloques:
  1. Cabecera: icono 26 dp + «DRACPASTE» (Archivo Black 24 sp) y bajada muted 14 sp: «Tu portapapeles compartido con el PC, sin nube y sin cuenta.»
  2. **Chips de promesa** (borde 1,5 dp ink, píldora, Roboto Mono 11 sp): «solo red local» · «sin historial» · «cifrado e2e».
  3. Tarjeta héroe (borde 2 dp ink, radio 16): diagrama de dos carriles (SVG en HTML; en Compose, `Canvas` o drawables), texto «Empareja tu PC una vez y lo que copies allí aparecerá aquí.», botón lleno accent «Escanear el código del PC», enlace «La cámara no funciona: pegar el texto».
  4. Nota muted: «En el PC, abre DracPaste desde la bandeja y pulsa “Emparejar un móvil”.»
  5. Fila «Ajustes y batería ›» tras separador line.
- El pie legal desaparece de aquí: las promesas están en los chips; la frase completa vive en Ajustes.

## A2. Principal — con PC(s) (`#4b`, `#4c`)
- **Botón primario nuevo**: tarjeta accent «Enviar lo copiado al PC» con subtítulo `móvil ─ ─[ toque ]──▶ {nombre}`. `DEV`: al pulsarlo se lee el portapapeles (hay foco) y se envía al PC activo.
- Tarjeta por PC: nombre 17 sp bold, «Huella A1B2 C3D4» en mono muted, mini-carril «PC ──▶ móvil · llega solo», enlace «Desemparejar».
- PC activo: **borde grueso ink (3 dp) + etiqueta sólida «DESTINO ACTIVO»** (fondo ink, texto paper). Inactivos: borde line 2 dp, fondo paper, botón hueco «Usar este PC». Con ≥2 PCs añadir «Los clips van solo al PC activo.»
- Nada de alturas fijas: al 150 % de fuente las tarjetas crecen y las etiquetas envuelven (ver `#4c`).

## A3. Escaneo QR (`#5a`)
- Fondo negro, título «Apunta al código del PC», visor 1:1 radio 16 con **cuatro esquinas blancas** (guía, trazo 3) y línea inferior «El código está en la ventana del PC». Botón hueco blanco «Cancelar». Sin animación de barrido.

## A4. Pegar texto — plan B (`#5b`)
- Título «Pegar el texto del PC». Explicación: «En la ventana del PC, pulsa “Copiar el texto” y pégalo aquí tal cual. Parece ruido — es normal, está cifrado a propósito.»
- Campo con **borde discontinuo** (zona de soltar, no formulario), contenido mono muted. Atajo «Pegar del portapapeles» (accent). Botón lleno «Emparejar con este texto». Validación solo en error.

## A5. Confirmación de huella (`#5c`) — CRÍTICA
- Título «Emparejado con {PC}». Texto: «Último paso: mira la pantalla del PC. Las dos huellas deben ser **exactamente** esta:»
- La huella como artefacto: marco 3 dp ink, radio 16, **34 sp Roboto Mono** con tracking, pictogramas «en el PC / aquí» debajo.
- Botones: lleno ink **«Coinciden»**; enlace danger **«No coinciden — desemparejar»** (`DEV`: ejecuta el desemparejado). No existe «Entendido».

## A6. Ajustes (`#6a`)
- Barra superior con **flecha atrás** (eliminar el enlace «Volver» del fondo). Título «Ajustes y batería».
- Interruptores M3: «Pausar la sincronización» (sub: «No sale ni entra nada. La conexión se mantiene.») y «Avisar al recibir un clip».
- Batería mal (tarjeta danger, borde 2 dp, fondo #faf1ec): título bold danger «Batería: con restricciones», UNA frase de consecuencia («Android puede matar la conexión cuando apagues la pantalla.»), botón lleno danger «Quitar las restricciones», y aviso de fabricante **plegado**: «Tu móvil es {marca}: hay un paso más ▾». Bien: «Batería: sin restricciones ✓» + sub muted.
- **Reglas ≠ ajustes**: sección «LAS REGLAS DE LA CASA — NO SE PUEDEN TOCAR» (Roboto Mono 11 sp, tracking, muted), ítems con cuadrado ink 8 dp y prosa corrida (no par título/sub): Solo red local / Cifrado e2e / Clips sensibles nunca (+ «no hay opción para activarlo. A propósito.») / Sin historial. Sin analíticas. Aquí va la frase legal completa.

## A7. Diálogo desemparejar y banner de actualización (`#6b`)
- Diálogo M3 estándar, textos literales del brief; «Desemparejar» en danger.
- Banner: misma anatomía en 4 estados — disponible (botón «Actualizar»), descargando (barra accent + «descargando… N %» + «Cancelar»), lista («Instalar»), error (frase + «Reintentar»). Plegable, jamás modal.

## A8. Notificación (`#6c`) — permanente, prioridad baja
- **Colapsada** (≈40 caracteres, sin botones): título = estado; texto conectado: **«Para enviar al PC: desplegar ↓»**.
- Títulos por estado (literales): Sin emparejar / Buscando tu PC / Conectando con {PC} / Conectado con {PC} / Reconectando con {PC} / Sincronización en pausa / Clip recibido, toca para pegarlo.
- **Desplegada** (BigTextStyle): «Lo que copies en el PC llega solo.\nLo del móvil no puede salir sin tu toque: Android lo exige.» Acciones: **Enviar portapapeles** · **Pausar** (y **Pegar** cuando Android bloqueó la escritura en segundo plano).
- Icono de barra de estado (VectorDrawable monocromo 24 dp, pendiente del SVG): conectado = cabeza sólida; reconectando = versión hueca/atenuada; los estados se distinguen por forma, no color.

---

# WINDOWS (WinForms, .NET 8 — todo controles de serie salvo lo marcado)

## W1. Bandeja (`#7a`)
- 4 iconos .ico pre-renderizados (NotifyIcon.Icon se cambia entero, sin pintar): conectado = cabeza sólida; reconectando = **al 38 % de opacidad**; sin móvil = atenuada + «✕» en la esquina; pausa = sólida + cuadradito hueco. En 16/20/24/32 px, versiones para barra clara y oscura.
- Menú: línea de estado deshabilitada («Conectado con {móvil}», etc.) · **«⚠ Permitir en el firewall de Windows…»** (bold, fondo #faf1ec, solo cuando falta la regla) · «Emparejar un móvil…» · «Ajustes…» · «Salir».
- Tooltip: `DracPaste · {estado}` ≤ 63 caracteres.

## W2. Emparejamiento — 420×620 fija (`#7b`)
- Bloques de arriba abajo (medidas actuales valen): instrucción (88 px) con «Escanear el código del PC» en negrita + «Este PC: {nombre} · {ip}:{puerto}»; QR 320 px; línea de huella; **ProgressBar** full-width; texto plan B; caja del JSON (Consolas 8 pt gris); botones «Copiar el texto» / «Cerrar».
- **Cuenta atrás = ProgressBar de serie que se vacía** + «El código caduca en **m:ss**» y «se renueva solo al caducar» a la derecha.
- `DEV`: **al llegar a 0 el código se regenera solo** y la barra rearranca. Nada de cerrar/reabrir.
- Huella: antes de emparejar, «Comprueba la huella al terminar» + sub «El móvil enseñará una huella; este PC mostrará la misma.»; al emparejar, «Huella: A1B2 C3D4» (Consolas bold 15) + «El móvil debe mostrar esta misma huella».
- **Firewall**: panel fijo (Panel+Label+Button de serie) bajo la barra de título mientras falte la regla: «**El firewall va a bloquear al móvil.** El móvil verá este PC pero la conexión morirá en un tiempo de espera.» + botón «Permitir en el firewall de Windows…». El globo se mantiene, pero ya no es la única señal.

## W3. Estado caducado — solo si falla la regeneración (`#7c`)
- Panel de **tinta (#1a1a1a) tapando el área del QR**: «El código ha caducado» (blanco, bold), «No se pudo generar uno nuevo. Comprueba la red y vuelve a intentarlo.» y botón «Generar un código nuevo» (borde blanco). Controles de serie con BackColor/ForeColor; sin GDI+.

## W4. Ajustes — 560×420 fija (`#7c`)
- Cabecera: «Este PC: {nombre}» bold + «Escuchando en el puerto {puerto} · solo red local · cifrado de extremo a extremo».
- ListView detalles: Móvil 180 / Huella 100 (Consolas) / Emparejado 130 / Estado 98. Fila conectada: «Conectado» en bold (la selección del ListView ya la resalta). Vacía: «Ningún móvil emparejado todavía» en gris.
- Casillas: «Arrancar DracPaste al iniciar sesión en Windows» y «Pausar la sincronización (nada sale ni entra)».
- Pie gris literal: «DracPaste no envía nada fuera de tu red local, no guarda historial de clips y no recoge ninguna estadística.» Botones «Desemparejar» / «Cerrar».

## W5. Globos
- Emparejado: «Emparejado con {móvil} · huella {huella}».
- Clip enorme: «Ese texto ocupa demasiado ({N} caracteres) y no se ha enviado al móvil.»
- Firewall: el globo actual se conserva como refuerzo del panel W2.

## DPI
125/150 %: nada depende de caber justo; ventanas fijas escalan con AutoScaleMode.Dpi. Comprobar que el JSON (caja plan B) sigue haciendo elipsis y no empuja los botones.

---

# Trabajo marcado `DEV` (cambios de comportamiento, no solo estilo)
1. Botón «Enviar lo copiado al PC» en la principal (leer portapapeles con foco y enviar).
2. Regeneración automática del código QR al caducar (Windows).
3. «No coinciden» en la confirmación de huella ejecuta el desemparejado.
4. Iconos .ico adicionales para reconectando / sin móvil / pausa.
5. Panel de firewall dentro de la ventana de emparejamiento.
6. Insets del sistema en Android (bug del reloj pisado).

# Pendientes de diseño (no bloquean)
- Vectorizar `assets/drac-head.png` → VectorDrawable de notificación, monocromo Material You, icono adaptativo (108/66 dp) y .ico multirresolución (16/32/48/256).
- Imágenes del instalador Inno Setup (164×314 y 55×58) y tarjeta/cabecera de DracApps.

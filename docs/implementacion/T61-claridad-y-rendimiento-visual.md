# T61 — Verificar claridad y rendimiento visual

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Base:** T60 aceptada al solicitar David T61; commit `6883d66`, 24 pruebas específicas y 910 totales correctas, BUILD SUCCESS y Git limpio.
- **Estado:** implementada y verificada en Linux headless; pendiente de Maven y revisión nativa de fase 10.

## Resultado y cambios de producción

BuildingPanel conserva pisos e IDs en los bordes visibles al desplazar el edificio. Los ejes se pintan al final sobre bandas claras: el margen izquierdo sigue indicando pisos al recorrer columnas y el encabezado sigue mostrando IDs al recorrer alturas. Los títulos parcialmente fuera del área útil se omiten para evitar restos ambiguos como «98» en lugar de «Elevador 9998». Un hueco/cabina parcialmente fuera del viewport se inspecciona desplazando la barra hasta mostrarlo completo.

El ancho preferido por columna tiene en cuenta la etiqueta del último ID y la fuente actual; la altura por piso tiene en cuenta la altura de fuente. Se conservan los mínimos de T58 (112 px por cabina, 36 px por piso) y sus coordenadas, incluida P=1. Las etiquetas se ajustan a sus rectángulos sin salir de la cabina o invadir el margen. El contraste conserva cabinas azules con texto blanco y etiquetas oscuras sobre fondo blanco. Se inspeccionaron rasters con fuente de 24 puntos, IDs largos y viewport desplazado.

Una caché de coordenadas enteras por columna visible decide si hace falta un nuevo fotograma. Si los píxeles no cambian, no se solicita repintado aunque avance el temporizador o llegue otro Frame equivalente. Esto cubre reposo, pausa, movimiento menor que un píxel y cabinas fuera del área vertical visible. Las pestañas ocultas no solicitan fotogramas. La caché se reemplaza al recorrer columnas: no acumula todos los elevadores visitados. El primer dibujo, desplazamiento o redimensionado exige una nueva invalidación de geometría.

Render conserva el Frame inmutable y consulta esa misma decisión de repintado, sin obligar a redibujar cada captura de 100 ms. El Timer de 16 ms, el capturador, el gate y el cierre T60 no se modifican. Tampoco cambian el reloj, el modelo, los trabajadores, los comandos, las prioridades o la herencia de solicitudes. No se añaden dependencias, hilos ni temporizadores de producción.

## Archivos

| Archivo | Contenido |
| --- | --- |
| `src/main/java/com/elevatormanager/ui/BuildingPanel.java` | Ejes visibles, ajuste de texto y caché de repintados |
| `src/test/java/com/elevatormanager/ui/VisualQualityScenarios.java` | 16 escenarios, pulsos y mediciones |
| `src/test/java/com/elevatormanager/ui/VisualQualityTest.java` | 16 adaptadores JUnit |
| `docs/implementacion/T61-codigo-completo.md` | Los tres Java completos, sin fragmentos |
| `docs/comprobaciones/T61-resultados-locales.md` | Mediciones emitidas por la ejecución final |
| `docs/comprobaciones/T61-validacion-local.md` | Compilación, regresión y límites de evidencia |
| `docs/comprobaciones/T61-recorrido-gui.md` | Matriz de escritorio y comandos Git |
| `docs/comprobaciones/T61-aceptacion-fase-10.md` | Estado de aceptación y cierre pendiente |

El plan y el registro de T60 se actualizan con los resultados ya aportados por David. El ZIP incluye el proyecto completo y las fuentes anteriores. Se mantienen Java 17, Maven, Swing, la entrada ElevatorManager, paquetes y actores independientes.

## Verificación visual automatizada

1. Etiquetas de pisos visibles tras desplazamiento horizontal, comprobadas como texto oscuro en el raster del margen.
2. Títulos de cabinas visibles tras desplazamiento vertical.
3. Fuente de 24 puntos e IDs hasta 10000: espacio suficiente y ausencia de títulos parcialmente recortados junto al margen fijo.
4. P=1: cabina separada de los ejes y raster identificable.
5. Viewport 280×220: acceso a última cabina y piso 1 sin ocultarlos bajo los ejes.
6. Redimensionado: invalida geometría y conserva el piso lógico.
7. Pausa: 120 avances de pared no cambian la decisión de raster.
8. Movimiento subpíxel: no solicita imágenes iguales; al cambiar la coordenada entera sí repinta.
9. Cien Frames IDLE nuevos sin cambio de posición no invalidan el raster.
10. Edificio oculto: RepaintManager no recibe solicitudes de animación.
11. Diez mil cabinas y cien mil pisos: caché acotada al viewport, sin componentes por piso.
12. Trescientos fotogramas con movimiento: no aumenta el total de hilos iniciados tras calentar fuentes/Java2D.
13. El EDT atiende diez pulsos mientras otro Timer dibuja; ambos se cierran.
14. Tres cabinas reales en movimiento, manejador y capturador: conservan sus IDs de hilo durante el dibujo.
15. Tres ciclos de sesión: terminan todos los actores y capturadores observados, sin acumulación.
16. Medición de 240 rasters por configuración para N/P = 1/1, 3/10, 20/100 y 1000/100000.

Los tests no exigen una velocidad artificial independiente del equipo. Se exige completar los pulsos dentro del plazo de prueba y se registran mediana, p95, máximo, CPU del EDT cuando está disponible y tamaño de la caché. El total de hilos iniciados, la identidad de actores y el tamaño de caché aportan oráculos de recursos; no se confunden con un perfil completo de heap o proceso.

Las mediciones utilizan un BufferedImage fijo de 800×450 reutilizado, dibujo recortado y viewport real. No simulan mil actores: el edificio virtual mide el pintor; el caso de actores reales usa tres cabinas. La escalabilidad del motor mantiene sus pruebas anteriores, que forman parte de la regresión.

## Límites y aceptación

Los resultados son de raster headless, no FPS de Windows, GPU, latencia de ratón o foco. Los rasters muestran que las etiquetas tienen espacio y permanecen visibles; el guion nativo verifica el resultado real con ventanas 1533×815/1366×768 y reducidas, barras, alternancia de pestañas y controles. Las dimensiones exactas dependen del área útil del equipo.

Los conteos próximos a Integer.MAX_VALUE siguen protegidos contra overflow y componentes masivos como en T58; no se promete legibilidad de miles de millones de etiquetas dentro del espacio de coordenadas int de Swing. Las pruebas de claridad usan magnitudes representativas y fuentes explícitas.

La demora nominal del Timer sigue siendo 16 ms con coalescencia. Se omiten repintados idénticos, pero el control y la captura conservan su actividad necesaria. No se afirma consumo cero de CPU en pausa ni una garantía de 60 FPS. La sincronización de posiciones de T60 permanece intacta y se vuelve a probar en regresión.

T61 y el cierre de fase 10 quedan pendientes de los resultados locales y aceptación nativa del acta. No se inicia T62 en esta entrega.

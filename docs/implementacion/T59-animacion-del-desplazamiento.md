# T59 — Implementar animación del desplazamiento

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Base aceptada:** T58, commit `712b24a`, 12 pruebas específicas y 868 totales con BUILD SUCCESS; David aporta estado Git sin salida y solicita T59.
- **Estado:** implementada y verificada en Linux headless; pendiente de Maven y aceptación visual local.
- **Alcance:** posiciones intermedias dentro de cada tramo, refresco Swing y separación entre dibujo y estado lógico.

## Resultado visible

La pestaña Edificio muestra las cabinas moviéndose entre centros de pisos, en subida o bajada. Las cabinas pueden avanzar simultáneamente en sentidos distintos. Un cambio de tamaño recalcula las coordenadas sin reiniciar el avance temporal. Cabinas conserva el último piso lógico confirmado y sus destinos/requerimientos.

La vista puede alcanzar el siguiente centro antes de recibir su confirmación; queda en ese centro hasta que el trabajador publique llegada u otro tramo. No inventa tramos futuros ni cambia currentFloor, nextFloor, dirección, colas o eventos. Las duraciones y la política de asignación siguen perteneciendo al motor.

## Archivos Java completos

| Archivo | Responsabilidad |
| --- | --- |
| `simulation/SimulationController.java` | Lectura pública activeTimeNanos del reloj activo, sin nuevas transiciones |
| `ui/UiSnapshotPump.java` | Captura temporal fuera del EDT y Frame con Timing opcional |
| `ui/CabinMotion.java` | Función pura de interpolación por tramo |
| `ui/BuildingPanel.java` | Reloj de presentación inyectable, posición fraccional y raster |
| `ui/SystemControlPanel.java` | Timer existente a 16 ms; conserva gate de captura/visor a 100 ms |
| `ui/CabinAnimationScenarios.java` (test) | 18 escenarios independientes |
| `ui/CabinAnimationTest.java` (test) | 18 adaptadores JUnit |

Las rutas de producción parten de src/main/java/com/elevatormanager y las de prueba de src/test/java/com/elevatormanager. T59-codigo-completo.md reproduce los siete archivos íntegros, incluidos los existentes modificados, sin parches ni elipsis. El ZIP incluye todas las fuentes anteriores, Java 17/Maven/Swing, la jerarquía Request/HallRequest/CabinRequest y los actores independientes ya aceptados.

## Dos relojes, un anclaje explícito

referenceNanos de un snapshot pertenece al reloj activo PausableClock compartido por la sesión. No tiene el mismo origen que System.nanoTime. Restar uno de otro produciría un avance incorrecto, normalmente una cabina pegada al extremo del tramo.

SimulationController.activeTimeNanos es una lectura del mismo reloj utilizado por los actores. UiSnapshotPump la consulta en su trabajador existente, después de obtener los snapshots y las asignaciones. Guarda esa marca activa y una marca de pared monotónica de presentación en Timing. La captura se descarta si cambia la época o el estado global observado durante el muestreo; sigue siendo una captura por cabina, no una transacción atómica global.

Timing.at estima el reloj activo a partir de la diferencia entre dos marcas de pared del mismo origen. Si la muestra no está avanzando, conserva su marca activa. No se resta nunca una marca de pared de referenceNanos. La pareja de marcas se toma en secuencia y puede tener el pequeño desfase de esa lectura, no exactitud física atómica. La lectura del reloj del motor y su lock permanecen fuera del EDT.

La muestra se declara activa durante RUNNING, STOPPING y RESETTING. En los otros estados no extrapola tiempo de pared. Un snapshot HELD de movimiento usa exclusivamente su progreso capturado. Estos son límites defensivos de la proyección; la coordinación inmediata de capturas y controles de pausa/Stop/reset corresponde a T60.

El constructor Frame anterior de cuatro argumentos se conserva. Una captura sin Timing utiliza solo el progreso ya publicado, sin inferir que su referenceNanos sea una marca de System.nanoTime. Esto mantiene compatibles los usos previos y las pruebas de T52/T58.

## Interpolación acotada

Para un snapshot MOVING:

- Tiempo consumido capturado: `duration - remaining`.
- Incremento posterior: `min(remaining, max(0, activeNow - reference))`.
- Progreso: `(consumido + incremento) / duration`.
- Posición visual: `currentFloor + (nextFloor - currentFloor) * progreso`.

El incremento nunca supera remaining; la suma no desborda duration y la posición permanece entre los dos pisos adyacentes del tramo. Las marcas monotónicas pueden ser negativas o envolver long; se utiliza su diferencia dentro del contrato de intervalos menores que Long.MAX_VALUE. Una marca anterior a la referencia no añade progreso.

HELD con tramo suspendido usa su progreso capturado sin incremento. IDLE, DWELLING y TERMINATED se dibujan en currentFloor. Pintar no consume presupuesto temporal ni confirma una llegada.

BuildingPanel convierte posiciones fraccionales a píxeles con la misma geometría T58. P=1 sigue sin dividir entre cero. Se conserva el recorte de pisos/columnas visibles, el desplazamiento y la ausencia de componentes por piso. El reloj se lee una vez por pintura, por lo que todas las cabinas del mismo raster usan la misma referencia de presentación.

## Refresco y cierre

Se reutiliza el único Timer de SystemControlPanel y se cambia su demora nominal de 100 a 16 ms. Conserva coalescencia: un EDT ocupado no acumula fotogramas para recuperarlos en ráfaga. Es una frecuencia solicitada, no una garantía de 60 FPS del sistema operativo.

El gate de 100 ms conserva la frecuencia de captura, visor y paneles. Entre esas capturas, cada tick invalida solo el rectángulo visible del edificio si está mostrado. No crea hilos ni ejecutores adicionales. La pintura no obtiene snapshots del motor, no espera futuros y no agrupa asignaciones en EDT. El cierre aceptado detiene el mismo Timer y termina el mismo trabajador de captura de T52.

La captura asíncrona introduce latencia de observación: un cambio de tramo puede no aparecer hasta recibir el siguiente Frame. Si este llega tarde, la posición se calcula desde su anclaje de captura, no desde la recepción. Si no hay nueva captura, el dibujo se detiene en el extremo del tramo publicado y no predice el resto de la ruta. Tramos más breves que el intervalo de observación pueden no disponer de fotogramas intermedios visibles.

## Verificación específica

Los 18 escenarios cubren inicio/mitad/final en subida, bajada, límites, progreso ya consumido, HELD, reposo/parada, orígenes de reloj diferentes, Frame repetido, captura nueva del mismo tramo, entrega tardía, espera visual de confirmación, captura sin Timing, muestra sin avance, wrap y duración máxima, raster intermedio, dos cabinas, redimensionado y captura con controlador/actores reales.

Los oráculos temporales usan un reloj virtual: no dependen de sleeps para acertar porcentajes. La integración real usa movimiento de 10000 ms para observar el primer tramo holgadamente y exige cierre efectivo. El detalle de regresión y entorno está en T59-validacion-local.md.

T59 no cierra fase 10: T60 verificará la sincronización animada durante pausa, Stop, reset y cierre; T61 revisará claridad y rendimiento visual. Se conservan esos alcances y no se atribuye aquí aceptación nativa de Windows.

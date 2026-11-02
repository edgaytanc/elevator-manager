# T61 — Resultados locales de claridad y rendimiento

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Origen:** ejecución final de VisualQualityScenarios durante la regresión, con fuentes definitivas.
- **Método:** 30 pinturas de calentamiento y 240 muestras por configuración; buffer de 800×450 reutilizado, viewport real situado en el extremo inferior derecho. Percentiles sobre muestras ordenadas; p95 usa el índice 227 de 240, según el código entregado.

## Medidas del pintor

| N | P | Fotogramas | Mediana ms | p95 ms | Máximo ms | CPU EDT del lote ms | Cabinas en caché |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 240 | 0.066 | 0.126 | 6.338 | 20.971 | 1 |
| 3 | 10 | 240 | 0.159 | 0.229 | 0.451 | 40.745 | 3 |
| 20 | 100 | 240 | 0.246 | 0.311 | 1.627 | 62.274 | 7 |
| 1000 | 100000 | 240 | 0.225 | 0.268 | 0.367 | 49.382 | 7 |

CPU corresponde al lote de 240 rasters del EDT cuando ThreadMXBean la suministra; -1 indicaría ausencia de dato. No es porcentaje de CPU del proceso ni consumo de GPU. Las cifras incluyen coste de raster/pintura observado, son específicas de este entorno y no se extrapolan a Windows o FPS de monitor. Se conservan máximos y percentiles, no solo el caso más rápido.

## Hilos y respuesta

```text
T61_THREADS|frames=300|started_before=9|started_after=9
T61_PULSE|ticks=10|max_gap_ms=20.295
T61_RENDER|N=1|P=1|frames=240|median_ms=0.066|p95_ms=0.126|max_ms=6.338|cpu_ms=20.971|cached=1
T61_RENDER|N=3|P=10|frames=240|median_ms=0.159|p95_ms=0.229|max_ms=0.451|cpu_ms=40.745|cached=3
T61_RENDER|N=20|P=100|frames=240|median_ms=0.246|p95_ms=0.311|max_ms=1.627|cpu_ms=62.274|cached=7
T61_RENDER|N=1000|P=100000|frames=240|median_ms=0.225|p95_ms=0.268|max_ms=0.367|cpu_ms=49.382|cached=7
```

En el caso aislado de 300 fotogramas en movimiento, el total de hilos iniciados permaneció igual tras el calentamiento. Otro caso verificó que tres actores de cabina, manejador y capturador conservaran sus cinco IDs de hilo mientras se dibujaba. Tres ciclos adicionales terminaron los actores/capturadores observados sin acumulación.

El pulso proviene de un Timer Swing de 20 ms mientras otro Timer de 16 ms dibuja. Se exige recibir diez pulsos dentro de cinco segundos y se reporta su separación máxima; no se convierte ese plazo de seguridad de prueba en una garantía de latencia de producción.

La caché permaneció entre una y siete columnas en estas configuraciones. El escenario de 10000 cabinas y 100000 pisos verificó además un límite de ocho entradas para viewport de 600 px y que el scroll no acumula IDs visitados. No se midió el heap total: el recurso acotado comprobado es la caché visible y el buffer fijo de la prueba. Los edificios virtuales grandes prueban el pintor; el caso con actores reales tiene tres cabinas.

## Decisión de repintado

Los oráculos verificaron 120 incrementos de pared en pausa, cien Frames IDLE nuevos y ticks subpíxel sin invalidar un raster idéntico. RepaintManager confirmó que una vista oculta no solicita fotogramas de animación. Esto reduce trabajo de pintura, pero no promete CPU cero ni elimina la captura/control necesarios.

La revisión nativa permanece pendiente en T61-recorrido-gui.md. Estos resultados no acreditan foco, ratón, barras nativas ni fluidez real de escritorio.

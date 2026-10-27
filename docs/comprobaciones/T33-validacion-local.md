# T33 — Evidencia de validación local

- **Fecha:** 2026-10-27.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Base:** producción hasta T32, sin cambios; dos archivos nuevos de pruebas T33.
- **Compilación:** --release 17, UTF-8; código de salida 0.

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/t33-classes @verification/t33-sources.txt
```

Se ejecutaron los main de los nueve grupos, todos con código de salida 0:

```text
T33: 20 escenarios satisfactorios; 15 de reloj explícito y 5 de integración con actores reales.
T32: 26 escenarios satisfactorios; pendientes, alias, confirmaciones y reintentos con hilos reales.
T31: 18 escenarios satisfactorios; selección por ETA, desempate y revalidación concurrente.
T30: 20 escenarios satisfactorios; incluye 60 rutas contrastadas con ElevatorState.
T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).
T25: 20 escenarios satisfactorios.
T26: 21 escenarios satisfactorios.
T27: 20 escenarios satisfactorios.
T28: 8 escenarios satisfactorios.
```

**Total: 165 escenarios: 20 nuevos y 145 de regresión.** Los sesenta subcasos de T30 no se cuentan nuevamente.

Rutas observadas por STOP_STARTED en los actores reales:

| Caso | Piso/sentido de las paradas |
| --- | --- |
| Compatible en subida | 3/UP, 6/UP |
| Contraria espera regreso | 6/UP, 3/DOWN |
| Ambos sentidos en piso 3 | 3/UP, 6/UP, 3/DOWN |
| Compatible en bajada | 5/DOWN, 2/DOWN |
| Llamada detrás del barrido | 7/UP, 2/DOWN |

Las trazas completas de T33 están en T33-trazas-locales.md. Se verificaron secuencia, llegadas adyacentes, frontera física antes de invertir, tiempos mínimos, servicio compatible y confirmación global SERVED. Los actores terminaron sin fallos.

No se ejecutó Maven aquí ni se creó un commit en el equipo de David. Las 249 pruebas acumuladas esperadas deben confirmarse en su repositorio. Esta ficha registra resultados observados; no es una transcripción de Surefire. Los archivos verification y sus binarios son temporales y no forman parte del ZIP.

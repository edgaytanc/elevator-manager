# T31 — Evidencia de validación local

- **Fecha:** 2026-10-26.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Base:** modelo/motor de entregas previas, estimador y captura de T30, selección de T31 y pruebas de T29 actualizadas a la nueva política.
- **Compilación:** --release 17, UTF-8; código de salida 0.

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/t31-classes @verification/t31-sources.txt
```

Se ejecutaron los main de los siete grupos, cada uno con código de salida 0:

```text
T31: 18 escenarios satisfactorios; selección por ETA, desempate y revalidación concurrente.
T30: 20 escenarios satisfactorios; incluye 60 rutas contrastadas con ElevatorState.
T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).
T25: 20 escenarios satisfactorios.
T26: 21 escenarios satisfactorios.
T27: 20 escenarios satisfactorios.
T28: 8 escenarios satisfactorios.
```

**Total: 119 escenarios: 18 nuevos y 101 de regresión.** Las sesenta rutas de T30 son subcasos de uno de sus veinte escenarios; no se suman nuevamente. Después del ajuste final para tomar capturas nuevas tras reservar el ID, se recompilaron las fuentes y se reejecutaron satisfactoriamente los grupos de selección T31 y manejador T29 afectados por ese ajuste. No se modificó el motor ni el estimador después de su regresión.

Se comprobaron costos exactos de 200 ms para una cabina libre frente a 510 ms de una cercana ocupada, comparación de costos mayores que Long.MAX_VALUE, desempate por ID, exclusiones, capturas obsoletas y cierre o cambio de candidatas antes del envío con trabajadores reales.

No se ejecutó Maven aquí ni se hizo un commit en el equipo de David. Las 203 pruebas acumuladas esperadas deben confirmarse en su repositorio. Esta ficha registra resultados observados; no es una transcripción de Surefire. Los archivos verification y sus binarios son temporales y no forman parte del ZIP.

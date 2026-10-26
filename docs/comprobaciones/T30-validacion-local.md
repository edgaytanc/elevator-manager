# T30 — Evidencia de validación local

- **Fecha:** 2026-10-26.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Base:** motor recuperado de las entregas anteriores, ManagerWorker de T29 y reemplazo completo de ElevatorWorker para publicar PlanningSnapshot.
- **Compilación:** --release 17, UTF-8; código de salida 0.

Comando ejecutado en este entorno, cuyo compilador está disponible como módulo:

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/t30-classes @verification/t30-sources.txt
```

Se ejecutaron los main de las seis clases de escenarios con código de salida 0, usando java -Djava.awt.headless=true y el directorio de clases anterior:

```text
T30: 20 escenarios satisfactorios; incluye 60 rutas contrastadas con ElevatorState.
T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).
T25: 20 escenarios satisfactorios.
T26: 21 escenarios satisfactorios.
T27: 20 escenarios satisfactorios.
T28: 8 escenarios satisfactorios.
```

**Total: 101 escenarios, de los cuales 20 son nuevos y 81 de regresión.** Las 60 rutas del contraste forman parte de uno de los 20 escenarios nuevos y no se cuentan nuevamente en el total.

Las comprobaciones calculadas a mano incluyen ETA de 160 ms con tramo restante, 210 ms con parada compartida, 610 ms por regreso y comparación de cabina cercana ocupada de 510 ms frente a cabina libre más lejana de 200 ms. Se verificaron inmutabilidad y coincidencia entre suma del desglose y tiempo total, así como resultados exactos mayores que Long.MAX_VALUE nanosegundos.

No se ejecutó Maven en este entorno ni se ha hecho un commit en el repositorio de David. Las 185 pruebas acumuladas esperadas deben confirmarse allí. Esta ficha registra resultados observados; no representa una transcripción de Surefire. Los archivos verification y sus binarios son temporales y no forman parte del ZIP.

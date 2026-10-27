# T34 — Evidencia de validación local

- **Fecha:** 2026-10-27.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Base:** producción validada hasta T32/T33, sin cambios; tres archivos nuevos de evaluación/pruebas T34.
- **Compilación:** --release 17, UTF-8; código de salida 0.

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/t34-classes @verification/t34-sources.txt
```

Los diez grupos siguientes terminaron con código de salida 0:

```text
T34: 13 escenarios satisfactorios; métricas verificadas y cargas completas con 1, 2 y N cabinas.
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

**Total: 178 escenarios: 13 nuevos y 165 de regresión.** Los subcasos de T30 y las ejecuciones internas de los escenarios no se cuentan nuevamente como pruebas JUnit.

Además se ejecutó la matriz predeterminada con ScalabilityEvaluation: tres cargas, N=1/2/4, tres repeticiones, 27 ejecuciones y 162 llamadas servidas. Se usaron 8 pisos, 25 ms entre pisos y 10 ms por parada. El proceso terminó con código 0 y sin salida de error. El informe generado se conserva íntegro en T34-resultados-locales.md.

El colector validó servicio compatible y único, secuencias, movimientos adyacentes, tiempos mínimos y cierre físico de paradas. El registro global confirmó SERVED y una asignación por llamada. Los actores terminaron sin fallos. Las pruebas ejercitaron también N=5 y validaron fórmulas de métricas, rotación del orden y rechazo de alias como muestras independientes.

No se ejecutó Maven aquí ni se creó un commit en el repositorio de David. Las 262 pruebas acumuladas esperadas deben confirmarse allí. Esta ficha registra resultados observados; no es una transcripción de Surefire. Los archivos verification y sus binarios son temporales y no forman parte del ZIP.

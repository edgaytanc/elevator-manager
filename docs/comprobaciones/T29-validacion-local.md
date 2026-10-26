# T29 — Evidencia de validación local

- **Fecha:** 2026-10-26.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing en modo headless.
- **Compilación:** producción recuperada de T17/T18/T20/T25/T26/T27, SimulationConfig de T16, pruebas T25–T28 y fuentes nuevas T29; objetivo --release 17, codificación UTF-8.
- **Resultado de compilación:** código de salida 0.

El entorno contiene el módulo jdk.compiler aunque no expone el ejecutable javac. Se utilizó su punto de entrada:

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/classes @verification/sources.txt
java -Djava.awt.headless=true -cp verification/classes com.elevatormanager.dispatch.ManagerWorkerScenarios
```

Salida observada:

```text
T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).
```

Se ejecutaron además los main de las cuatro clases siguientes, cada uno con código de salida 0:

```text
T25: 20 escenarios satisfactorios.
T26: 21 escenarios satisfactorios.
T27: 20 escenarios satisfactorios.
T28: 8 escenarios satisfactorios.
```

**Total ejecutado: 81 escenarios, de los cuales 12 son nuevos y 69 de regresión.** Esta es una ficha de resultados observados, no una transcripción de Maven. Los archivos verification y sus clases compiladas son temporales del entorno de comprobación y no forman parte de la entrega.

Maven y la integración completa deben validarse en el repositorio de David con los comandos de docs/implementacion/T29-hilo-del-manejador.md. No se ha ejecutado aquí la suite acumulada de 165 pruebas esperadas ni se ha creado un commit en su equipo.

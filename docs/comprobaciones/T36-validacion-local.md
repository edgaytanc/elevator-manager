# T36 — Validación local

- **Fecha:** 2026-10-27, Guatemala.
- **Base:** T35, commit `9a5f7f4` y 276 pruebas Maven confirmadas por David.
- **Entorno local:** Linux, OpenJDK 17.0.20, ejecución headless.
- **Resultado final:** compilación Java 17 y 252 escenarios satisfactorios en 16 grupos; sin fallos de los procesos de prueba.

## 1. Compilación real

Se compilaron todos los fuentes de producción y los escenarios independientes con objetivo Java 17. Las clases de target del ZIP original no se utilizaron. El entorno dispone del módulo compilador, aunque no tiene javac ni Maven en PATH. Desde la raíz Maven:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.StopContinuationScenarios
```

Resultado: `T36: 21 escenarios satisfactorios.` El caso 21 incluye 4,000 ciclos Stop/Start dentro de ese escenario; no se contabilizan como 4,000 pruebas JUnit adicionales.

## 2. Regresión final

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.346 |
| ElevatorSelectionScenarios | 18 | 0 | 1.531 |
| ManagerWorkerScenarios | 12 | 0 | 1.203 |
| PendingConfirmationScenarios | 26 | 0 | 1.652 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.224 |
| ScalabilityScenarios | 13 | 0 | 4.608 |
| ModelRulesScenarios | 7 | 0 | 0.118 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.218 |
| DirectionalAgendaScenarios | 20 | 0 | 3.440 |
| ElevatorDwellScenarios | 15 | 0 | 0.675 |
| ElevatorMovementScenarios | 10 | 0 | 0.191 |
| ElevatorWorkerScenarios | 7 | 0 | 0.175 |
| ExecutionEventScenarios | 20 | 0 | 0.929 |
| ParallelTimingScenarios | 8 | 0 | 4.658 |
| SimulationControllerScenarios | 14 | 0 | 1.515 |
| StopContinuationScenarios | 21 | 0 | 5.337 |

Los tiempos son observaciones de esta ejecución; no son garantías de rendimiento. El total de escenarios no equivale a toda la suite JUnit: existen pruebas de modelo que solo tienen adaptadores JUnit en el proyecto.

## 3. Verificación intensiva y defecto corregido

La primera versión produjo un TimeoutException durante ciclos consecutivos. Se localizaron dos carreras en la espera del manejador: una confirmación antigua retenida durante una barrera nueva y un wake consumido antes de volver a mailbox.await(). En una ejecución de diagnóstico de la segunda carrera, el manejador quedó WAITING en el mailbox y el controlador siguió STOPPING en el ciclo 3902. No fue un fallo de servicio ni una prueba Maven.

Se corrigieron la identidad de la barrera y la revalidación de controles antes de dormir. Sobre las clases corregidas se ejecutaron 5,000 ciclos con cabina en reposo y 5,000 con una parada abierta. Ambas ejecuciones terminaron satisfactoriamente. La regresión final de la tabla se ejecutó después de la corrección y el escenario permanente añade 2,000 ciclos de cada modalidad.

Los ciclos intensivos no prueban ausencia de cualquier carrera futura. T40 ampliará los casos de reset y ciclo de vida cuando existan los controles correspondientes.

## 4. Validación que falta en el equipo de David

Maven no está instalado en este entorno. No se ejecutó mvn clean verify aquí ni se declara una ejecución JUnit acumulada local. Ejecutar los comandos de la ficha T36:

- StopContinuationTest: 21 pruebas esperadas.
- Grupo afectado indicado en la ficha: 128 esperadas.
- mvn clean verify: 297 acumuladas esperadas sobre las 276 de T35.

Confirmar las cifras reales y BUILD SUCCESS antes de marcar T36 cerrada. No hay nueva aceptación visual manual: la ventana operativa y sus botones se implementan en fase 9. Se verificó la respuesta del EDT mediante eventos Swing en los escenarios.

El plan marca T35 cerrada con la evidencia aportada por el usuario; T36 permanece pendiente del cierre Maven. T37–T40 siguen abiertas.

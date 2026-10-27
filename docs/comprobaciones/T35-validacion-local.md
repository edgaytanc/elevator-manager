# T35 — Validación local

- **Fecha:** 2026-10-27.
- **Base:** commit `94a81f2` del ZIP recibido.
- **Entorno:** Linux, OpenJDK 17.0.20, ejecución headless.
- **Resultado:** fuentes de producción y escenarios compilados con objetivo Java 17; 15 grupos, 231 escenarios satisfactorios.

## Método reproducible local

El entorno tiene el módulo jdk.compiler pero no el comando javac en PATH. Se compiló mediante su entrada de módulo estándar:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.SimulationControllerScenarios
```

Todos los escenarios se ejecutaron desde las clases recién compiladas; no se utilizaron los .class de target del ZIP. Los mismos métodos de T35 tienen 14 adaptadores JUnit en SimulationControllerTest.

## Regresión ejecutada

| Grupo | Escenarios | Código de salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.353 |
| ElevatorSelectionScenarios | 18 | 0 | 1.580 |
| ManagerWorkerScenarios | 12 | 0 | 1.232 |
| PendingConfirmationScenarios | 26 | 0 | 1.689 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.176 |
| ScalabilityScenarios | 13 | 0 | 4.612 |
| ModelRulesScenarios | 7 | 0 | 0.122 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.214 |
| DirectionalAgendaScenarios | 20 | 0 | 3.467 |
| ElevatorDwellScenarios | 15 | 0 | 0.688 |
| ElevatorMovementScenarios | 10 | 0 | 0.234 |
| ElevatorWorkerScenarios | 7 | 0 | 0.184 |
| ExecutionEventScenarios | 20 | 0 | 0.943 |
| ParallelTimingScenarios | 8 | 0 | 4.662 |
| SimulationControllerScenarios | 14 | 0 | 1.537 |

Las duraciones son observaciones de esta ejecución; no son límites de rendimiento ni parte de la aceptación. Los escenarios incluyen pruebas de temporización, eventos, despacho y métricas previas.

## Límites de esta comprobación

Maven no está instalado aquí. No se ejecutó mvn clean verify ni se afirma haber corrido la suite JUnit acumulada. En el equipo de David se deben ejecutar los comandos de la ficha T35: 14 pruebas específicas y 276 acumuladas esperadas sobre la base de 262 de T34. La ventana sigue siendo la de T11; no se realizó aceptación gráfica manual nueva.

T35 queda implementada y comprobada por escenarios; el cierre formal espera la validación Maven del usuario. No se cierran T36–T40.

## Confirmación posterior en el equipo de David

El 2026-10-27 se verificó la salida adjunta de Maven: SimulationControllerTest, 14 pruebas sin fallos, errores u omisiones; construcción completa, 276 pruebas sin fallos, errores u omisiones y BUILD SUCCESS. Se confirmó el commit `9a5f7f4` y árbol limpio. T35 queda cerrada; la limitación de Maven descrita arriba corresponde a la ejecución local inicial.

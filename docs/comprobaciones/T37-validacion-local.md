# T37 — Validación local

- **Fecha:** 2026-10-27, Guatemala.
- **Base:** T36, commit `1f9b0c0`, con 297 pruebas Maven confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, ejecución headless.
- **Resultado:** fuentes compilados con objetivo Java 17 y 284 escenarios satisfactorios en 17 grupos; ningún proceso final de escenario falló.

## 1. Compilación

Se compilaron todos los fuentes de producción y los escenarios independientes desde el código actual, sin usar los .class del ZIP original. El módulo compilador está disponible aunque no hay javac ni Maven en PATH. Desde la raíz Maven:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.CabinResetScenarios
```

Salida: `T37: 32 escenarios satisfactorios.` CabinResetTest contiene los 32 adaptadores JUnit de los mismos casos.

## 2. Resultados finales por grupo

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.314 |
| ElevatorSelectionScenarios | 18 | 0 | 1.569 |
| ManagerWorkerScenarios | 12 | 0 | 1.219 |
| PendingConfirmationScenarios | 26 | 0 | 1.672 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.202 |
| ScalabilityScenarios | 13 | 0 | 4.622 |
| ModelRulesScenarios | 7 | 0 | 0.172 |
| CabinResetScenarios | 32 | 0 | 7.345 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.223 |
| DirectionalAgendaScenarios | 20 | 0 | 3.467 |
| ElevatorDwellScenarios | 15 | 0 | 0.692 |
| ElevatorMovementScenarios | 10 | 0 | 0.208 |
| ElevatorWorkerScenarios | 7 | 0 | 0.169 |
| ExecutionEventScenarios | 20 | 0 | 0.940 |
| ParallelTimingScenarios | 8 | 0 | 4.680 |
| SimulationControllerScenarios | 14 | 0 | 1.537 |
| StopContinuationScenarios | 21 | 0 | 5.525 |

Las duraciones son observaciones locales, no garantías de rendimiento. Los cien resets del escenario de épocas sucesivas y los ciclos de Stop de T36 son iteraciones internas; no se suman como nuevas pruebas JUnit.

## 3. Ajustes de comprobación durante T37

La primera pasada de los escenarios nuevos detectó que el fixture leía el resultado de admisión de un reset anterior como si fuera la respuesta de la siguiente orden de servicio. Se corrigió correlacionando el comando completo, incluida época y tipo, antes de comprobar su resultado. No fue una falsa admisión de época antigua en producción.

Dos escenarios históricos todavía esperaban que un reset válido se rechazara por no estar implementado. Se adaptaron para usar época no creciente, manteniendo la comprobación de rechazos y ausencia de falsa finalización. Se actualizó la explicación del rechazo de reset en la prueba directa del registro de servicio T26. ExecutionEventScenarios se volvió a ejecutar tras adaptar el caso; los demás grupos de la tabla ya habían pasado con el código de producción final. El grupo T37 se ejecutó completo al añadir la cancelación explícita de recogida externa.

## 4. Evidencia funcional observada

- Reloj explícito: un tramo de 100 ms observado a los 40 ms conserva 60 ms, llega en el instante original y después retorna mediante otro tramo completo.
- Ascenso interrumpido: llegadas físicas [2, 1], sin llegar al antiguo destino 5.
- Retorno desde piso 5: llegadas [4, 3, 2, 1], sin saltos de piso ni paradas de servicio.
- Parada activa de 60,000 ms: se descarta y el reset termina dentro del plazo de ocho segundos de prueba.
- Inbox retenido de cien comandos: cien rechazos y una admisión de reset, sin iniciar movimiento de servicio.
- Canónica y alias admitidos: cancelaciones correlacionadas en época anterior, sin falsear COMMAND_COMPLETED.
- Agenda externa: cancelación de la recogida y agenda vacía en piso 1.
- Dos cabinas: una presta servicio en su época original mientras la otra retorna por reset local.
- Repetición y cierre: una operación física por control repetido; un cierre interrumpido no confirma RESET_COMPLETED.

Los detalles de pasos y aserciones están en CabinResetScenarios.java y en el lector íntegro de código T37. No se declara implementado ni probado un reset global.

## 5. Validación pendiente en el equipo de David

Maven no está instalado aquí; no se ejecutó mvn clean verify ni la suite JUnit acumulada local. Ejecutar los comandos de la ficha de implementación y confirmar BUILD SUCCESS:

- CabinResetTest: 32 pruebas esperadas.
- Grupo seleccionado de regresión: 160 esperadas.
- Construcción completa: 329 acumuladas esperadas sobre las 297 de T36.

No hay una nueva ventana ni aceptación gráfica manual de reset. La coordinación de N cabinas y el manejador queda para T38; el cierre integrado y las carreras ampliadas permanecen en T39–T40. T37 queda pendiente de cierre Maven en el plan.

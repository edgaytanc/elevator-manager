# T40 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T39, commit `74d467d`, 394 pruebas Maven confirmadas por David.
- **Entorno local:** Linux, OpenJDK 17.0.20, headless; Maven no está instalado.
- **Resultado:** compilación Java 17, 373 escenarios independientes en 20 grupos, salida 0 en todos. La versión final de T40 pasa cinco ejecuciones adicionales de sus 24 escenarios.

## 1. Compilación

Se compilaron los fuentes actuales de producción y todos los escenarios independientes en check-classes, fuera del proyecto. No se utilizaron los .class preexistentes de target.

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.LifecycleRaceScenarios
```

Salida: `T40: 24 escenarios satisfactorios.` Tras reforzar las aserciones de orden Start/Stop y Stop/reset, se recompiló LifecycleRaceScenarios y se repitió cinco veces; la tabla siguiente incorpora el tiempo de la primera ejecución final de ese grupo. No hubo cambios de producción.

## 2. Regresión independiente

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.610 |
| ElevatorSelectionScenarios | 18 | 0 | 1.647 |
| ManagerWorkerScenarios | 12 | 0 | 1.261 |
| PendingConfirmationScenarios | 26 | 0 | 1.750 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.253 |
| ScalabilityScenarios | 13 | 0 | 4.703 |
| ModelRulesScenarios | 7 | 0 | 0.177 |
| CabinResetScenarios | 32 | 0 | 7.490 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.297 |
| DirectionalAgendaScenarios | 20 | 0 | 3.544 |
| ElevatorDwellScenarios | 15 | 0 | 0.734 |
| ElevatorMovementScenarios | 10 | 0 | 0.256 |
| ElevatorWorkerScenarios | 7 | 0 | 0.257 |
| ExecutionEventScenarios | 20 | 0 | 1.042 |
| GlobalResetScenarios | 33 | 0 | 8.381 |
| LifecycleRaceScenarios | 24 | 0 | 3.238 |
| OrderlyShutdownScenarios | 32 | 0 | 1.748 |
| ParallelTimingScenarios | 8 | 0 | 4.770 |
| SimulationControllerScenarios | 14 | 0 | 1.641 |
| StopContinuationScenarios | 21 | 0 | 6.016 |

Los 373 escenarios corresponden a los grupos independientes con main. No equivalen a toda la suite JUnit: las pruebas de modelo sin main se validan mediante Maven en el equipo de David. Los tiempos observados no son garantías de rendimiento.

## 3. Cinco repeticiones finales de T40

| Ejecución | Escenarios | Salida | Duración s |
| ---: | ---: | ---: | ---: |
| 1 | 24 | 0 | 3.238 |
| 2 | 24 | 0 | 3.229 |
| 3 | 24 | 0 | 3.476 |
| 4 | 24 | 0 | 3.401 |
| 5 | 24 | 0 | 3.596 |

Cada ejecución se realizó en una JVM nueva. Son repeticiones de los mismos 24 casos, no 120 pruebas JUnit distintas. Los ciclos internos de 20, 30, 40 o 100 iteraciones tampoco incrementan el recuento de casos.

## 4. Evidencia y límites

- Ocho productores preparados antes de liberar el latch ejercitan Start y Stop simultáneos.
- Los resultados y estados se comparan con los órdenes válidos del coordinador; no se exige un orden de llegada fijo.
- Cada carrera espera lanzamientos y confirmaciones con un presupuesto común de diez segundos.
- Reset confirma todas las cabinas en piso 1, dirección UP, fase IDLE, modo NORMAL y sin compromisos, hold ni pendientes; el registro externo queda vacío.
- Un cierre durante reset y durante Stop interrumpe la espera física de un tramo configurado en 60,000 ms y resuelve los futuros pendientes como rechazo por cierre.
- Mensajes extraídos antes de reset se descartan al entregarse después en EDT; una confirmación de la época nueva sí se aplica.
- Seis historiales anteriores no completan el comando nuevo; se comprueban época, UUID canónico y commandId.
- Cien carreras mezcladas conservan exactamente las referencias Thread de tres cabinas y un manejador.
- Las sesiones y productores cierran sus ejecutores, comprueban ciclos TERMINATED y no conservan referencias capturadas con isAlive verdadero.
- Las capturas de hilos se verifican con la ejecución secuencial predeterminada del proyecto; no se habilitó JUnit paralelo.

La preparación mediante latch provoca competencia real; no explora exhaustivamente todos los entrelazados posibles del planificador. Las precondiciones basadas en eventos observan estados reales sin congelar la ejecución de los actores. Los límites acotados detectan bloqueos durante la prueba y no prometen tiempos de producción bajo cualquier carga. El estrés general, las pausas y las funciones aún no implementadas permanecen en T69 y sus tareas respectivas.

No se ejecutó Maven/JUnit ni una ventana JFrame aquí. Los 24 adaptadores JUnit delegan a los mismos escenarios, pero deben compilarse y ejecutarse con las dependencias reales en el equipo de David.

## 5. Validación pendiente en Windows

```powershell
mvn -Dtest=LifecycleRaceTest test
mvn "-Dtest=LifecycleRaceTest,OrderlyShutdownTest,GlobalResetTest,CabinResetTest,StopContinuationTest,SimulationControllerTest,ManagerWorkerTest,ExecutionEventTest" test
mvn clean verify
```

Se esperan 24 específicas, 188 seleccionadas y 418 acumuladas, sin fallos, errores ni omisiones. Confirmar las cifras reales y BUILD SUCCESS antes de marcar T40 completada. Commit sugerido: `T40: probar carreras del ciclo de vida`.

T39 ya tiene Maven y commit confirmados; aún falta confirmar manualmente retorno al prompt al cerrar con Cerrar y con X. La fase 6 queda abierta hasta completar esa comprobación y la validación/incorporación de T40.

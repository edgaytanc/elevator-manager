# T39 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T38, commit `f4b654c`, con 362 pruebas Maven confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, ejecución headless.
- **Resultado:** compilación con objetivo Java 17 y 349 escenarios satisfactorios en 19 grupos; todos los procesos finales terminaron con salida 0.

## 1. Compilación y ejecución

Se compilaron todos los fuentes de producción y los escenarios independientes desde sus archivos actuales. No se utilizaron los .class de target del ZIP original. El módulo compilador está disponible aunque no hay javac ni Maven en PATH. Desde la raíz Maven:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.OrderlyShutdownScenarios
```

Salida: `T39: 32 escenarios satisfactorios.` OrderlyShutdownTest contiene los 32 adaptadores JUnit de los mismos casos.

## 2. Regresión final

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.662 |
| ElevatorSelectionScenarios | 18 | 0 | 1.886 |
| ManagerWorkerScenarios | 12 | 0 | 1.363 |
| PendingConfirmationScenarios | 26 | 0 | 1.878 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.631 |
| ScalabilityScenarios | 13 | 0 | 5.437 |
| ModelRulesScenarios | 7 | 0 | 0.265 |
| CabinResetScenarios | 32 | 0 | 7.674 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.633 |
| DirectionalAgendaScenarios | 20 | 0 | 3.684 |
| ElevatorDwellScenarios | 15 | 0 | 0.952 |
| ElevatorMovementScenarios | 10 | 0 | 0.378 |
| ElevatorWorkerScenarios | 7 | 0 | 0.650 |
| ExecutionEventScenarios | 20 | 0 | 1.438 |
| GlobalResetScenarios | 33 | 0 | 8.934 |
| OrderlyShutdownScenarios | 32 | 0 | 1.809 |
| ParallelTimingScenarios | 8 | 0 | 4.836 |
| SimulationControllerScenarios | 14 | 0 | 1.727 |
| StopContinuationScenarios | 21 | 0 | 6.666 |

Los tiempos son observaciones locales, no garantías de rendimiento. Los grupos independientes no equivalen a toda la suite JUnit: hay pruebas de modelo sin main. Los productores concurrentes son iteraciones internas, no 64 nuevos tests independientes.

## 3. Evidencia funcional

- closeAsync completa CLOSED sin que awaitTermination tenga que impulsar el cierre.
- Un tramo y una parada configurados con 60,000 ms se cierran dentro del plazo de diez segundos de prueba, sin simular una llegada ni esperar esos tiempos.
- Los recursos se liberan después de terminar manejador y cabinas y en orden inverso de registro.
- Sesenta y cuatro solicitudes concurrentes de cierre ejecutan una sola liberación de un recurso registrado.
- Un recurso bloqueado mantiene CLOSING; una espera corta devuelve false sin cancelar el cierre, que termina después de liberar el latch.
- Fallos de recursos conservan sus causas, intentan los restantes y no producen CLOSED exitoso.
- Un recurso rechazado sigue siendo del llamador y no lo cierra el controlador.
- Los callbacks visuales se ejecutan en EDT; el puente con una sesión real no dispone antes de liberar su recurso, y el EDT procesa un pulso durante esa espera.
- Un fallo de la etapa visual no llama a disposición.
- Handlers propios se desconectan y cierran una sola vez, dejando intactos los ajenos.
- Se crea un FileHandler real, se verifica la eliminación de su .lck, se renombra su archivo y se reabre el mismo destino sin un archivo alternativo por bloqueo. Los temporales se eliminan.
- Las referencias capturadas de actores, coordinador y cerrador ya no están vivas tras una terminación exitosa.

Las fallas de recursos son casos esperados que los escenarios comprueban expresamente; no son procesos de prueba fallidos. No se inyectaron fallos de creación de hilos ni se afirma probar todas las condiciones de infraestructura.

## 4. Límites y validación pendiente

Maven no está instalado aquí. No se ejecutó mvn clean verify ni se declara ejecución JUnit acumulada local. Ejecutar los comandos de la ficha y confirmar los resultados reales:

- OrderlyShutdownTest: 32 pruebas esperadas.
- Grupo seleccionado: 164 esperadas.
- Construcción completa: 394 acumuladas esperadas sobre las 362 de T38.

No hay sesión gráfica ni Xvfb en el entorno local. MainWindow se compiló; su manejador se probó sin JFrame con EDT real y un controlador real, pero no se presenta como realizada una comprobación visual de botón/X. David debe ejecutar el JAR dos veces y comprobar retorno al prompt primero con Cerrar y después con la X.

La entrada mínima actual no crea todavía una sesión; la configuración de fase 9 conectará la ventana a su controlador. Las pruebas automáticas de cierre del motor y handlers sí utilizan actores y recursos reales. T39 queda pendiente de Maven y comprobación visual; T40 mantiene abierta la fase 6.

# T38 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T37, commit `29538a6`, con 329 pruebas Maven confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, ejecución headless.
- **Resultado:** compilación con objetivo Java 17 y 317 escenarios satisfactorios en 18 grupos; todos los procesos finales terminaron con salida 0.

## 1. Compilación y ejecución

Se compilaron todos los fuentes de producción y los escenarios independientes a partir de sus archivos actuales. No se usaron los .class de target del ZIP original. El módulo compilador está disponible aunque no se dispone de javac ni Maven en PATH. Desde la raíz Maven:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d ../../check-classes $(find src/main/java -name '*.java')
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp ../../check-classes -d ../../check-classes $(find src/test/java -name '*Scenarios.java') src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java
java -Djava.awt.headless=true -cp ../../check-classes com.elevatormanager.simulation.GlobalResetScenarios
```

Salida final: `T38: 33 escenarios satisfactorios.` GlobalResetTest contiene los 33 adaptadores JUnit de los mismos casos.

## 2. Regresión ejecutada

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.558 |
| ElevatorSelectionScenarios | 18 | 0 | 1.982 |
| ManagerWorkerScenarios | 12 | 0 | 1.370 |
| PendingConfirmationScenarios | 26 | 0 | 1.795 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.359 |
| ScalabilityScenarios | 13 | 0 | 4.923 |
| ModelRulesScenarios | 7 | 0 | 0.307 |
| CabinResetScenarios | 32 | 0 | 7.729 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.581 |
| DirectionalAgendaScenarios | 20 | 0 | 3.556 |
| ElevatorDwellScenarios | 15 | 0 | 0.916 |
| ElevatorMovementScenarios | 10 | 0 | 0.313 |
| ElevatorWorkerScenarios | 7 | 0 | 0.333 |
| ExecutionEventScenarios | 20 | 0 | 1.129 |
| GlobalResetScenarios | 33 | 0 | 8.492 |
| ParallelTimingScenarios | 8 | 0 | 4.821 |
| SimulationControllerScenarios | 14 | 0 | 1.819 |
| StopContinuationScenarios | 21 | 0 | 6.605 |

Los tiempos son observaciones locales, no garantías de rendimiento. El número de escenarios independientes no equivale a toda la suite JUnit: existen pruebas de modelo sin main independiente. Los productores concurrentes y ciclos repetidos son iteraciones internas, no cientos de nuevos tests JUnit.

## 3. Evidencia de la barrera y de invalidación

- Con dos cabinas, la que termina primero no basta para completar el reset global: se conserva RESETTING hasta la confirmación de la restante.
- Reset sustituye un Stop pendiente como REJECTED; su confirmación tardía no provoca un fallo ni cambia el reset.
- Una parada de 60,000 ms se cancela y el retorno global termina dentro del plazo de diez segundos de prueba.
- Treinta y dos productores concurrentes reciben la misma época de reset y se observa un único retorno local.
- Se comprueban 200 ciclos Reset/Start/Stop y una operación final de reset con dos cabinas, sin recrear actores ni interbloquear las esperas.
- Un historial de más de 256 eventos anteriores se depura sin devolverlo como actividad de la nueva época.
- Un evento viejo con secuencia Long.MAX_VALUE no atiende una llamada nueva ni invalida sus secuencias.
- Un comando antiguo asociado a un snapshot nuevo tampoco pasa isCurrent.
- El manejador detenido cancela su inbox, adopta la época y confirma con el registro vacío.
- Un despacho controladamente en vuelo impide la confirmación de limpieza hasta salir de esa operación; los eventos antiguos almacenados se ignoran al continuar.
- La época agotada no vuelve a un valor negativo ni inicia actores parcialmente.
- El EDT responde mientras transcurre un retorno; el cierre durante reset resuelve su futuro sin fingir RESET.

No se inyectaron fallos internos de infraestructura ni se declara probado todo orden de ejecución posible. T40 ampliará los escenarios de carreras de ciclo de vida. Los escenarios y aserciones completos están en GlobalResetScenarios.java y en el lector de código T38.

## 4. Límites y comprobación pendiente

Maven no está instalado en este entorno; no se ejecutó mvn clean verify ni se declara una ejecución JUnit acumulada local. En el equipo de David ejecutar los comandos de la ficha de implementación:

- GlobalResetTest: 33 pruebas esperadas.
- Grupo seleccionado: 158 esperadas.
- Construcción completa: 362 acumuladas esperadas sobre las 329 de T37.

Confirmar las cifras reales y BUILD SUCCESS antes de cerrar T38. La GUI operativa, importación de lotes, cierre con handlers y extras mantienen sus tareas correspondientes; no se realizó una nueva aceptación gráfica manual de botones. El plan marca T37 cerrada con el commit confirmado y deja T38 pendiente de Maven.

## 5. Confirmación posterior en el equipo de David

Se verificó el 2026-10-28 la salida adjunta: GlobalResetTest, 33 pruebas específicas; grupo seleccionado, 158; construcción completa, 362. Todas sin fallos, errores u omisiones y BUILD SUCCESS. Se confirmó el commit `f4b654c`, comprobaciones de diferencias sin advertencias y árbol limpio. T38 queda cerrada; las limitaciones de Maven descritas arriba corresponden a la ejecución local inicial.

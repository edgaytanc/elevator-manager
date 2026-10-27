# T33 — Probar reglas de dirección y recogida en ruta

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-27.
- **Versión:** 0.1.0-SNAPSHOT; objetivo Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Etiqueta:** CALIDAD.
- **Base confirmada:** T32, commit `83f1b93`, 229 pruebas sin fallos ni errores, BUILD SUCCESS y árbol limpio.
- **Estado:** pruebas preparadas; 20 escenarios nuevos y 145 de regresión ejecutados localmente. Pendiente integración y validación Maven en el proyecto de David antes del cierre y commit.

## 1. Objetivo y alcance

Comprobar llamadas compatibles por delante, incompatibles por detrás y de sentido contrario en el mismo piso. El recorrido comprometido debe completarse antes de invertir, y la atención debe distinguir los dos sentidos.

Se prueban los contratos de ElevatorState y la integración ManagerWorker/ElevatorWorker/HallRequestLedger mediante eventos reales y confirmaciones SERVED. Se conserva la arquitectura con hilos, herencia de solicitudes, agendas locales, deduplicación y selección por ETA/ID. No se modifica producción ni se agregan dependencias Maven.

## 2. Archivos de la entrega

| Ruta | Acción y contenido |
| --- | --- |
| src/test/java/com/elevatormanager/dispatch/DirectionPickupScenarios.java | Nuevo: 15 casos con reloj explícito y 5 recorridos con actores reales. |
| src/test/java/com/elevatormanager/dispatch/DirectionPickupTest.java | Nuevo: 20 pruebas JUnit de los mismos escenarios. |
| docs/implementacion/T33-direccion-y-recogida-en-ruta.md | Nuevo: reglas, cobertura, integración y cierre. |
| docs/comprobaciones/T33-validacion-local.md | Nuevo: resultados observados. |
| docs/comprobaciones/T33-trazas-locales.md | Nuevo: trazas reales de las cinco ejecuciones de integración. |

El ZIP conserva estas cinco rutas desde la raíz Maven. No contiene pom.xml, binarios ni reemplazos de clases de producción. Se incorpora sobre el proyecto completo de T32. Las clases y pruebas anteriores permanecen en el repositorio.

## 3. Reglas verificadas

| Situación | Comportamiento esperado |
| --- | --- |
| Llamada del mismo sentido por delante | Recoger al pasar antes del destino comprometido. |
| Llamada de sentido contrario por delante | Pasar sin recoger y atender después, con el sentido compatible. |
| Llamada detrás de la cabina | Mantener la frontera actual y completar el recorrido antes de volver. |
| Dos sentidos en el mismo piso | Conservar llamadas distintas y atender cada una en una parada compatible. |
| Llamada contraria en una parada abierta con compromiso posterior | No incorporarla a esa parada; completar el compromiso y retornar. |
| Llamada compatible durante una parada abierta | Incorporarla sin reiniciar el tiempo de espera. |
| Llamada en el piso recién dejado | No servir durante el tramo ni adelantar el piso lógico. |
| Destino interno que amplía la frontera | Completar la extensión antes de regresar por una llamada opuesta. |
| Llamada externa más allá de la frontera | No ampliar el barrido vigente ni omitir un regreso pendiente. |
| Cabina libre en el piso solicitado | Puede cambiar de sentido para recoger sin inventar movimiento. |

La prohibición de invertir corresponde al recorrido comprometido. No obliga a una cabina libre a viajar en su dirección histórica ni impide cambiar de sentido alineada al terminar una frontera.

## 4. Matriz de los veinte escenarios

| N.º | Método | Comprobación principal |
| --- | --- | --- |
| 1 | ascendingCompatibleCallPrecedesDestination | Paradas 3/UP y 6/UP. |
| 2 | descendingCompatibleCallPrecedesDestination | Paradas 5/DOWN y 2/DOWN. |
| 3 | oppositeAheadWaitsForAscendingBoundary | No sirve 3/DOWN al subir; completa 6 y regresa. |
| 4 | oppositeAheadWaitsForDescendingBoundary | No sirve 5/UP al bajar; completa 2 y regresa. |
| 5 | callBehindDoesNotReverseAscendingCommitment | Desde 3 hacia 7, la llamada 2/DOWN espera. |
| 6 | callBehindDoesNotReverseDescendingCommitment | Desde 6 hacia 2, la llamada 7/UP espera. |
| 7 | oppositeAtOpenUpStopWaitsForReturn | Parada 3/UP no incorpora DOWN; ruta 3/UP, 6/UP, 3/DOWN. |
| 8 | oppositeAtOpenDownStopWaitsForReturn | Caso simétrico: 6/DOWN, 2/DOWN, 6/UP. |
| 9 | compatibleCallJoinsOpenStopWithoutRestart | Después de 10 ms, conserva 40 ms de una parada de 50 ms. |
| 10 | departedFloorIsNotServedDuringMovement | Volver físicamente al piso antes de recoger; invertir allí si hace falta. |
| 11 | bothDirectionsAtOneFloorHaveSeparateServices | Una atención por llamada, con su sentido. |
| 12 | externalBeyondFrontierDoesNotSkipReturn | Ruta 4/UP, 2/DOWN, 6/UP. |
| 13 | internalExtensionFinishesBeforeOppositePickup | Ruta 4/UP, 7/UP, 2/DOWN; tramo sin reinicio. |
| 14 | oppositeAtTopChangesDirectionWithoutInventingLeg | Dos servicios en piso 8, UP y DOWN, sin tramo ficticio. |
| 15 | idleCabinCanServeOppositeHistoricalDirection | Libre en 4, recoge DOWN aunque conserve dirección histórica UP. |
| 16 | managerCollectsCompatibleBeforeCommittedDestination | Manejador y trabajador reales: 3/UP antes de 6/UP. |
| 17 | managerDefersOppositeUntilReturn | Integración: 6/UP antes de 3/DOWN. |
| 18 | managerSeparatesBothDirectionsAtSameFloor | Integración: 3/UP, 6/UP, 3/DOWN; canónicas distintas. |
| 19 | managerCollectsDescendingBeforeCommittedDestination | Integración descendente: 5/DOWN antes de 2/DOWN. |
| 20 | managerDoesNotReverseForCallBehind | Integración: desde 3 hacia 7, completa 7/UP y luego recoge 2/DOWN. |

## 5. Casos con reloj explícito

Los quince primeros usan ElevatorState real con 100 ms entre pisos y 50 ms de parada. El reloj se adelanta explícitamente a los límites físicos; no se usa Thread.sleep. El caso elige el barrido y las primitivas de producción autorizan el movimiento y el servicio.

Se comprueba el orden exacto de paradas piso/sentido, conservación de pendientes diferidas, una atención por UUID y ausencia de recogidas en movimiento. Las pruebas no fabrican ExecutionEvent para simular confirmaciones del manejador.

## 6. Integración con hilos y eventos reales

Los cinco casos finales ejecutan un ManagerWorker y un ElevatorWorker independientes, con N=1 para controlar la ruta física. Los destinos internos y el manejador comparten una AtomicLong de IDs. Se configuran 200 ms de movimiento y 40 ms de parada.

Un evento DEPARTED confirma el comienzo del recorrido antes de insertar la llamada externa. Después se verifica el COMMAND_ACCEPTED de esa llamada: dirección y frontera deben seguir siendo las originales. Para una recogida por delante, el piso lógico de admisión debe estar antes del piso solicitado; el caso falla explícitamente si perdió esa ventana. Así no acepta como prueba de recogida en ruta un pedido ingresado después de pasar.

Para la llamada detrás se comprueba que el piso de admisión estaba realmente por encima del piso solicitado durante un compromiso ascendente. El recorrido debe llegar a su frontera antes de cualquier inversión posterior.

Se leen los eventos del canal original de ElevatorWorker. ManagerWorker consume su suscripción adicional de T32. La prueba espera todas las finalizaciones requeridas y el fin de la última parada; luego exige SERVED en el registro global, una sola asignación y una finalización correlacionada por llamada.

La traza se valida además con estas invariantes:

- Secuencia local consecutiva y reloj monotónico.
- Cada salida va a un piso adyacente en su dirección.
- Cada llegada corresponde a una salida previa.
- No se solapan un tramo y una parada.
- La recogida se completa en DWELLING, en el piso y sentido solicitados.
- Un commandId no se completa dos veces.
- Cada tramo dura al menos el tiempo configurado y cada parada al menos su espera.
- No existe un cambio de dirección desde la primera salida hasta alcanzar la frontera comprometida.
- Ambos actores terminan dentro del plazo y no conservan fallos.

Se usan diferencias de nanoTime registradas por el actor, no tiempos de lectura de colas ni igualdad exacta del planificador. Las esperas tienen un plazo de ocho segundos por objetivo de observación. No se promete duración de pared exacta ni rendimiento bajo carga arbitraria; la eficiencia y escalabilidad se evaluarán en T34.

## 7. Trazas incluidas

docs/comprobaciones/T33-trazas-locales.md contiene la salida observada de las cinco ejecuciones con campos sequence, tiempo relativo, tipo, piso, siguiente piso, sentido, fase, commandId y UUID de solicitud. Los UUID y tiempos cambian en cada ejecución; son evidencia de referencia, no un archivo esperado para comparación textual.

En los casos que requieren posicionar inicialmente la cabina, los eventos de preparación se consumen y la traza de comprobación se reinicia. Por eso su primera secuencia puede ser mayor que 1 y su primer tiempo relativo no tiene que ser cero. Dentro de cada traza comprobada las secuencias siguen siendo consecutivas. El cierre agrega WORKER_TERMINATED al registro impreso.

## 8. Validación local

Compilación Java 17 con --release 17 y ejecución directa en JVM de:

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| DirectionPickupScenarios, T33 | 20 |
| PendingConfirmationScenarios, T32 | 26 |
| ElevatorSelectionScenarios, T31 | 18 |
| PickupTimeEstimatorScenarios, T30 | 20 |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 165 |

No hubo fallos en esos grupos. No se modificaron clases de producción para hacer pasar las pruebas. Maven no está disponible en este entorno; la suite acumulada debe confirmarse en el repositorio de David.

## 9. Incorporación y validación en PowerShell

Desde `D:\Users\David\Desktop\workspace\java\elevator-manager`:

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T33-direccion-y-recogida-en-ruta.zip" -DestinationPath . -Force
mvn -Dtest=DirectionPickupTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest,PickupTimeEstimatorTest,ElevatorSelectionTest,PendingConfirmationTest,DirectionPickupTest" test
mvn clean verify
```

Ajustar la ruta de descarga. Se esperan veinte pruebas específicas, 197 del grupo de regresión y 249 totales si se conserva la base de 229 de T32. Confirmar con la salida real de Surefire y BUILD SUCCESS.

Para conservar la ejecución local de David:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=DirectionPickupTest test 2>&1 | Tee-Object -FilePath .\logs\t33-direccion-y-recogida-en-ruta.log
```

Después de compilar se pueden ejecutar los mismos escenarios sin JUnit:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.DirectionPickupScenarios
```

## 10. Cierre y commit después de validar

Actualizar el estado de esta ficha con los resultados reales y marcar solo T33 como completada en docs/01-analisis-y-plan.md. Añadir el plan al mismo commit:

```powershell
git diff --check
git add -- src/test/java/com/elevatormanager/dispatch/DirectionPickupScenarios.java src/test/java/com/elevatormanager/dispatch/DirectionPickupTest.java docs/implementacion/T33-direccion-y-recogida-en-ruta.md docs/comprobaciones/T33-validacion-local.md docs/comprobaciones/T33-trazas-locales.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T33: probar direccion y recogida en ruta"
git status --short
git log -1 --oneline
```

Siguiente tarea después del cierre: T34 — Evaluar eficiencia y escalabilidad. T33 no cierra por sí sola toda la fase 5.

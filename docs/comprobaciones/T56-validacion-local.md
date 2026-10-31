# T56 — Validación local

- **Fecha:** 29 de octubre de 2026, Guatemala.
- **Base confirmada por David:** T55 `dd9dc82`; 29 específicas, 810 totales, cero fallos/errores/omitidas, BUILD SUCCESS, staged diff correcto y árbol limpio.
- **Entorno de esta entrega:** Linux, OpenJDK 17.0.20, sin Maven ni escritorio.
- **Compilación:** módulo jdk.compiler, --release 17 -encoding UTF-8; todas las fuentes de producción y escenarios independientes, sin reutilizar target. Adaptadores JUnit excluidos de esta compilación por no disponer aquí de sus dependencias.
- **T56:** 14 escenarios de prioridades/trazas y 14 escenarios Swing satisfactorios.
- **Regresión independiente:** 39 grupos ejecutables satisfactorios con los recursos del proyecto en el classpath.
- **JUnit esperado:** 28 pruebas específicas y 838 totales. No se ejecutó Maven/JUnit en este entorno.

## Prioridades y trazas — 14 casos

| Caso | Resultado exigido |
| --- | --- |
| Liberar barrera parcial | Stop/Reset sustituyen sin exigir ACK de actores ausentes |
| Despertar actor ya bloqueado | Sale de Condition sin interrupción ni Resume |
| Stop desde tramo pausado | Llega al piso adyacente, conserva destino y no abre servicio |
| Stop desde parada pausada | HELD/STOP y resto conservado para Start |
| Reset desde tramo pausado | Completa tramo pendiente, cancela servicio anterior y retorna físicamente |
| Reset desde parada pausada | Retorna/limpia sin reanudar manualmente |
| Carrera Pausa/Stop | Ambos futuros resueltos; termina STOPPED |
| Carrera Pausa/Reset | Ambos futuros resueltos; una confirmación tardía no restaura PAUSED |
| Reset sustituye Stop iniciado en pausa | RESET limpio y una nueva época |
| Cierre sustituye Reset iniciado en pausa | Actores terminados y CLOSED sin fallo de motor |
| Pausa durante STOPPING | Rechazo sin congelar el retorno a punto seguro |
| Reset repetido en retorno | Una sola época por operación vigente |
| Transiciones y razones | Estados y sustituciones presentes en manejador.log sin duplicar pausa repetida |
| Confirmaciones tardías | Doce carreras; la cantidad de SIMULATION_PAUSED coincide con pausas efectivamente confirmadas |

## Controles Swing — 14 casos

1. Pausar/Reanudar bloqueados sin sesión y en RESET; Pausar habilitado en RUNNING.
2. PAUSING provisional inmediato, sin Resume anticipado, con Stop/Reset/Cerrar disponibles.
3. Matriz PAUSED: Reanudar/Stop/Reset/Cerrar disponibles; Start/Pausar/carga y solicitudes bloqueados.
4. Reanudar conserva sesión, época, destino, luz y progreso; atiende y apaga después.
5. Stop desde movimiento pausado, con continuación mediante Start.
6. Reset desde movimiento pausado y carga habilitada solo al confirmar retorno.
7. Pausar y Stop en el mismo turno EDT: no se restaura PAUSED por respuesta vieja.
8. Pausar y Reset en el mismo turno EDT: prevalece la nueva época y la vista vigente.
9. Cerrar desde PAUSED libera actores y capturador.
10. Cerrar inmediatamente después de pulsar Pausar resuelve la carrera sin Resume manual.
11. Visor muestra SIMULATION_PAUSED y SIMULATION_RESUMED por el canal real de logs.
12. Cinco ciclos de botones conservan la misma sesión.
13. STOPPING, STOPPED y RESETTING bloquean pausa/reanudación.
14. Reset desde pausa restaura importación mediante controles.

Los escenarios usan actores, SessionLog, widgets Swing y Timer reales. El selector de archivo es un proveedor de ruta para evitar el diálogo nativo; el lector/importador de producción sí se utiliza. Las acciones GUI de negocio nacen de botones, campos y validación del formulario. El controlador se consulta como oráculo de estado. No se invocan sus controles para sustituir los botones de estas pruebas.

## Comprobaciones temporales y de concurrencia

El caso Stop desde pausa exige completar el restante del tramo en el reloj activo antes de ARRIVED, sin COMMAND_COMPLETED ni STOP_STARTED antes de Start. El caso Reset exige la primera llegada al siguiente piso congelado antes de retornar a 1/UP, conservando el restante y evitando completar la solicitud cancelada. La parada detenida conserva un resto positivo y estable; Start debe consumirlo completo. No se usa un límite superior estrecho dependiente del planificador del sistema operativo.

Las esperas de pruebas permiten ocho segundos para motor/eventos, tres para salida del actor de barrera y diez para observaciones Swing; JUnit aplica treinta segundos por prueba. Los hilos y sesiones se liberan con try-with-resources/finally y cierre confirmado. La barrera parcial se prueba explícitamente sin depender de observar PAUSING durante un intervalo breve. Las carreras con hilos reales permiten que la pausa llegue a confirmarse antes del control superior; en ambos órdenes se exige resolución, estado final correcto y ausencia de resurrecciones.

## Regresión independiente

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.312 |
| ElevatorSelectionScenarios | 0 | 1.566 |
| ManagerWorkerScenarios | 0 | 1.21 |
| PendingConfirmationScenarios | 0 | 1.67 |
| PickupTimeEstimatorScenarios | 0 | 0.185 |
| ScalabilityScenarios | 0 | 4.474 |
| ReadFailureScenarios | 0 | 0.117 |
| RequestFileScenarios | 0 | 0.26 |
| LoggingScenarios | 0 | 0.397 |
| SessionLoggingScenarios | 0 | 1.954 |
| ModelRulesScenarios | 0 | 0.111 |
| CabinResetScenarios | 0 | 7.346 |
| ConcurrentAdmissionScenarios | 0 | 0.204 |
| DirectionalAgendaScenarios | 0 | 3.438 |
| ElevatorDwellScenarios | 0 | 0.68 |
| ElevatorMovementScenarios | 0 | 0.201 |
| ElevatorWorkerScenarios | 0 | 0.172 |
| ExecutionEventScenarios | 0 | 0.932 |
| GlobalPauseScenarios | 0 | 6.355 |
| GlobalResetScenarios | 0 | 7.816 |
| ImportReadErrorScenarios | 0 | 1.211 |
| LifecycleRaceScenarios | 0 | 2.76 |
| OrderlyShutdownScenarios | 0 | 1.548 |
| ParallelTimingScenarios | 0 | 4.643 |
| PausePriorityScenarios | 0 | 3.826 |
| RequestImportScenarios | 0 | 1.705 |
| SimulationControllerScenarios | 0 | 1.533 |
| StopContinuationScenarios | 0 | 5.312 |
| PausableClockScenarios | 0 | 0.103 |
| PauseCoordinatorScenarios | 0 | 0.084 |
| CabinPanelsScenarios | 0 | 4.536 |
| ConfigurationFormScenarios | 0 | 1.217 |
| EdtSafetyScenarios | 0 | 3.127 |
| EventViewerScenarios | 0 | 2.035 |
| FileSelectionScenarios | 0 | 1.234 |
| GuiJourneyScenarios | 0 | 12.519 |
| HallCallsScenarios | 0 | 2.337 |
| PauseControlsScenarios | 0 | 6.036 |
| SystemControlsScenarios | 0 | 4.739 |

Después de la regresión completa se ajustó la condición de la etiqueta provisional de Pausar para que no oculte el contador N+1 cuando el controlador ya está en PAUSING. Se recompilaron las fuentes y se repitió PauseControlsScenarios: 14/14 correctos con el cambio final. La tabla conserva ese último resultado para el grupo GUI. No se repitieron los demás grupos sin cambios adicionales que los afectaran.

Estos ejecutables independientes no equivalen a un informe Maven/Surefire. Headless comprueba integración y acceso seguro al EDT, pero no acredita interacción física con ratón, foco, X ni geometría del escritorio. El guion T56-recorrido-gui.md deja esas comprobaciones explícitamente pendientes.

## Revisión de entrega

Diez fuentes Java nuevas/modificadas, íntegras en T56-codigo-completo.md y en sus rutas Maven. Se actualiza el caso provisional de T55 a la nueva prioridad, manteniendo sus 17 pruebas. No cambia pom.xml ni se añaden dependencias. La herencia del dominio, los hilos independientes, el algoritmo de asignación y los canales de eventos se conservan.

Las fuentes y documentos T56 se revisaron sin espacios finales ni líneas vacías extra al EOF. Git diff --check sin errores en archivos rastreados; para las fuentes nuevas se comprobó también el formato directamente. El ZIP se verifica con CRC y comparación entrada por entrada contra los bytes de origen. Contiene el proyecto completo sin .git, target ni logs. No se crea ningún commit ni se simula el hash confirmado por David.

## Validación pendiente en Windows

Extraer en una carpeta temporal y copiar el contenido de elevator-manager sobre el repositorio local conservando .git. Ejecutar desde la raíz:

```powershell
mvn "-Dtest=PausePriorityTest,PauseControlsTest" test
mvn clean verify
git diff --check
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperado: 28 específicas y 838 totales, cero fallos, errores y omitidas; BUILD SUCCESS. Completar el guion T56-recorrido-gui.md para validar pausa/reanudación, prioridades, botón Cerrar y X durante pausa y barra horizontal de controles. Las transiciones nuevas se inspeccionan en manejador.log y el visor; las cabinas conservan sus registros físicos.

Tras confirmar Maven y el recorrido nativo, registrar resultados y marcar T56 completada. Mantener T57 pendiente para la aceptación ampliada y cierre de fase 9.

## Commit sugerido

```powershell
git status --short
git diff --check
git add .
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "T56: integrar controles de pausa y prioridades"
git status --short
git log -1 --oneline
```


## Validación confirmada por David — 29 de octubre de 2026

PausePriorityTest y PauseControlsTest: 28/0/0/0; mvn clean verify: 838/0/0/0, ambos BUILD SUCCESS. Commit `7dfb8e5` (T56: integrar controles de pausa y prioridades); git diff --cached --check sin observaciones y git status --short sin salida. El archivo Texto pegado(20261029-065631).txt muestra el arranque del JAR, tres pares SIMULATION_PAUSING/PAUSED/RESUMED y el cierre normal del manejador y ambas cabinas, con retorno al prompt. No identifica uso de Stop/Reset ni cierre durante pausa, ni permite diferenciar Cerrar de X. No se inventan esas evidencias: el recorrido nativo conserva los pendientes y T57 amplía la aceptación temporal automatizada.

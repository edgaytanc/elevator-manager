# T31 — Implementar selección y desempate

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-26.
- **Versión:** 0.1.0-SNAPSHOT; objetivo Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Base confirmada:** T30, commit `e7dd3c6`, 185 pruebas sin fallos y BUILD SUCCESS; árbol limpio.
- **Estado:** implementación aplicada y validada localmente. Pendiente integración y validación en el entorno de David antes de cerrar y confirmar T31.

## 1. Objetivo y resultado

El manejador sustituye el reparto circular provisional por selección del menor tiempo de recogida calculado con PickupTimeEstimator. El desempate usa ID ascendente. Antes del envío se comprueban las capturas y ciclos de vida observados; la cabina ganadora compara su captura y encola bajo su propio lock.

El resultado DISPATCHED sigue significando envío al buzón. La admisión y el servicio se confirman por separado. El seguimiento de recibido/asignado/atendido, deduplicación global y tratamiento de confirmaciones corresponde a T32.

## 2. Archivos del ZIP

| Ruta | Acción |
| --- | --- |
| src/main/java/com/elevatormanager/dispatch/ElevatorSelector.java | Nuevo: clasificación pura y motivos de exclusión. |
| src/main/java/com/elevatormanager/dispatch/ManagerWorker.java | Reemplazar completo: selección, revalidación y envío. |
| src/main/java/com/elevatormanager/simulation/ElevatorWorker.java | Reemplazar completo: submitIfCurrent. |
| src/test/java/com/elevatormanager/dispatch/ElevatorSelectionScenarios.java | Nuevo: dieciocho escenarios. |
| src/test/java/com/elevatormanager/dispatch/ElevatorSelectionTest.java | Nuevo: dieciocho pruebas JUnit. |
| src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java | Reemplazar completo: actualizar expectativas de reparto y contención de T29. |
| src/test/java/com/elevatormanager/dispatch/ManagerWorkerTest.java | Reemplazar completo: nombre de la prueba de selección con N cabinas. |
| docs/implementacion/T31-seleccion-y-desempate.md | Nuevo: contrato y validación. |
| docs/comprobaciones/T31-validacion-local.md | Nuevo: evidencia local. |

Todos los archivos están completos y conservan sus rutas desde la raíz Maven. Se incorporan sobre el proyecto de T30. No se reemplaza pom.xml, PickupTimeEstimator, PlanningSnapshot ni la jerarquía Request/HallRequest/CabinRequest. Se mantienen el hilo del manejador y los hilos independientes por cabina.

## 3. Selección determinista

ElevatorSelector.rank recibe configuración, llamada externa, lista de Candidate y una referencia común observedAtNanos. Candidate agrupa PlanningSnapshot y Lifecycle. La función no lee trabajadores, no modifica agendas, no consulta el reloj y no envía comandos.

Ranking devuelve listas inmutables de Choice y Excluded. Cada Choice conserva la captura evaluada y el Estimate completo de T30, incluido su desglose. Se ordena primero por pickupNanos, comparando BigInteger exactamente, y luego por elevatorId ascendente. No se convierte el costo a long o double y no se usa el orden de entrada ni un cursor circular para desempatar.

Con las mismas capturas, llamada y referencia temporal, la clasificación es idéntica incluso si cambia el orden de entrada de las cabinas. Los IDs repetidos y las llamadas inválidas para el edificio producen IllegalArgumentException.

| Motivo de exclusión | Condición |
| --- | --- |
| NOT_RUNNING | NEW, CLOSING, TERMINATED o FAILED. |
| OTHER_CONFIGURATION | Configuración distinta de la sesión. |
| OTHER_EPOCH | Época distinta de la llamada. |
| NOT_ESTIMABLE | T30 no devuelve un costo; incluye HELD, TERMINATED físico, RESET_RETURN o conflicto de identidad pendiente. |

El Lifecycle es necesario: una cabina terminada puede conservar un último snapshot físico NORMAL. Un resultado sin costo nunca se interpreta como cero. Los empates exactos pueden favorecer repetidamente al ID menor; no se promete reparto uniforme. T34 evaluará eficiencia y escalabilidad.

## 4. Despacho y revalidación

ManagerWorker mantiene constructor, start, submit, awaitResult, close, awaitTermination, Lifecycle y Result. Solo cambia la política de selección.

1. Validar la llamada y su época en el hilo del manejador.
2. Capturar las cabinas y comprobar que existe alguna opción estimable. Si no existe, devolver REJECTED sin reservar un ID de comando.
3. Reservar una sola vez el commandId mediante el generador compartido de sesión. Después de esa llamada se toman capturas y tiempo nuevos, para que el costo no sea anterior a una posible demora del generador.
4. Capturar de nuevo todas las candidatas y usar System.nanoTime después de la captura para obtener una referencia común.
5. Estimar, ordenar y elegir la primera Choice.
6. Revalidar la identidad de PlanningSnapshot y el Lifecycle de todas las cabinas, incluidas las excluidas: una candidata que antes perdía podría haber mejorado.
7. Si algo cambió, recalcular toda la clasificación. Si sigue vigente, llamar a submitIfCurrent en la ganadora.
8. Solo ENQUEUED produce DISPATCHED. STALE o UNAVAILABLE no encolan y permiten otro cálculo.

Hay como máximo tres intentos de selección/envío después de reservar el ID. El ID se reutiliza entre intentos que no encolaron nada. No se vuelve a enviar después de ENQUEUED. Si no aparece una opción o persiste la contención, se devuelve REJECTED con la solicitud original y un detalle explícito. El llamador conserva esa entrada para reintentar; la coordinación automática de pendientes se implementará en T32.

El detail de un despacho incluye ETA en nanosegundos y la regla de desempate. No se espera el movimiento ni la parada para procesar la siguiente llamada. No se ejecuta la selección desde el EDT ni se conserva el lock del manejador al invocar una cabina.

## 5. Envío condicionado por cabina

ElevatorWorker agrega Submission con ENQUEUED, STALE y UNAVAILABLE, y el método submitIfCurrent(Command, PlanningSnapshot).

El método verifica RUNNING y compara por identidad la captura publicada con la evaluada. Ambas comprobaciones y la inserción ocurren bajo el mismo ReentrantLock local que protege las transiciones del actor. Un objeto reconstruido con los mismos valores no representa la captura vigente para este contrato. STALE y UNAVAILABLE no agregan comandos ni generan una admisión ficticia.

submit(Command) conserva su comportamiento para los productores y pruebas anteriores. El método nuevo no reserva la cabina ni sustituye la validación de CommandLedger. Tampoco garantiza que los comandos previos del buzón estén incluidos en la agenda ya publicada.

## 6. Límites de consistencia

La clasificación es determinista sobre una entrada estable. Las capturas de cabinas distintas no constituyen una transacción global: una cabina no seleccionada puede cambiar inmediatamente después de su revalidación. El envío condicionado cierra la carrera entre la captura de la ganadora y su encolado, sin tomar locks de varias cabinas a la vez.

No se promete una decisión globalmente óptima bajo cambios continuos, ni que el estado permanezca igual hasta la admisión. Las llegadas futuras, carga del planificador y comandos pendientes en buzones conservan las limitaciones documentadas en T30. El despacho no consume todavía los eventos ni las confirmaciones de cabinas. El servicio real sigue siendo COMMAND_COMPLETED.

## 7. Actualización de las pruebas de T29

La prueba circularDispatchSupportsN se renombra a dispatchSupportsNWithCostSelection. Con tres cabinas y llamadas en piso 1 UP, todas tienen ETA cero y debe ganar el ID 1, incluso si la lista se entregó invertida. Conservar el resultado circular habría probado una política que T31 reemplaza.

La prueba de cuatro productores conserva 48 entradas y verifica un resultado explícito por UUID, ausencia de duplicados, IDs únicos en los despachos y progreso. Bajo contención admite REJECTED sin comando, conforme al límite de intentos; no acepta desaparición silenciosa ni cancelación inesperada. Las demás pruebas de T29 se conservan: EDT, cierre, épocas, IDs compartidos, errores y N=1. Siguen siendo doce pruebas.

## 8. Validación local

Compilación Java 17 con --release 17 y ejecución directa en JVM:

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| ElevatorSelectionScenarios, T31 | 18 |
| PickupTimeEstimatorScenarios, T30 | 20 |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 119 |

Los escenarios nuevos verifican costo menor aunque el ID sea mayor, empate independiente del orden de entrada, cabina cercana ocupada, exclusión por ciclo/configuración/época/fase, costos mayores que long, referencia temporal común, inmutabilidad, entradas inválidas, envío condicionado vigente/obsoleto/cerrado y despacho real.

Las carreras se controlan mediante el generador inyectado de IDs ya existente: durante esa llamada se cierra una ganadora o se mueve una candidata con trabajadores reales. No se añaden hooks de prueba a producción ni se usan sleeps. Se comprueba también que una perdedora que mejora vuelve a evaluarse y que cerrar todas las cabinas produce rechazo sin envío.

No se ejecutó Maven en este entorno. Debe confirmarse la suite completa en el proyecto de David.

## 9. Incorporación y pruebas en PowerShell

Desde la raíz `D:\Users\David\Desktop\workspace\java\elevator-manager`:

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T31-seleccion-y-desempate.zip" -DestinationPath . -Force
mvn -Dtest=ElevatorSelectionTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest,PickupTimeEstimatorTest,ElevatorSelectionTest" test
mvn clean verify
```

Ajustar la ruta de descarga. Aceptar el reemplazo completo de las dos clases de producción y las dos pruebas de T29 indicadas. Se esperan 18 pruebas específicas, 151 del grupo de regresión y 203 totales si se mantiene la base de 185 de T30. Confirmar con la salida real de Surefire y BUILD SUCCESS.

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=ElevatorSelectionTest test 2>&1 | Tee-Object -FilePath .\logs\t31-seleccion-y-desempate.log
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.ElevatorSelectionScenarios
```

## 10. Cierre y commit después de validar

Actualizar el estado de esta ficha con los resultados reales y marcar solo T31 como completada en docs/01-analisis-y-plan.md. Añadir el plan al mismo commit:

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/dispatch/ElevatorSelector.java src/main/java/com/elevatormanager/dispatch/ManagerWorker.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/dispatch/ElevatorSelectionScenarios.java src/test/java/com/elevatormanager/dispatch/ElevatorSelectionTest.java src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java src/test/java/com/elevatormanager/dispatch/ManagerWorkerTest.java docs/implementacion/T31-seleccion-y-desempate.md docs/comprobaciones/T31-validacion-local.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T31: implementar seleccion por costo y desempate determinista"
git status --short
git log -1 --oneline
```

Siguiente tarea después del cierre: T32 — Coordinar pedidos pendientes y confirmaciones.

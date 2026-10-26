# T29 — Implementar el hilo del manejador

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-26.
- **Versión:** 0.1.0-SNAPSHOT; compilación Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Base:** fases 1–4 cerradas por David; última referencia recuperada: T28, commit `286508f`.
- **Estado:** integración y validación local completadas. Integración y validación con Maven completadas.

## 1. Objetivo

Consumir llamadas externas desde una cola propia, procesarlas en un hilo independiente del EDT y despachar recogidas incluso con N = 1.

Se mantiene la entrada `com.elevatormanager.app.ElevatorManager`, Swing, Maven, el objetivo Java 17, la jerarquía real Request/HallRequest/CabinRequest y el motor concurrente de T22–T28. ManagerWorker se agrega al paquete dispatch, separado del trabajador físico de cada cabina en simulation.

## 2. Entrega e incorporación

El ZIP contiene archivos completos de esta tarea, con rutas relativas a la raíz Maven. Tiene el mismo formato incremental de T27/T28: se incorpora sobre el proyecto existente, sin sustituirlo por una reconstrucción. No incluye pom.xml, archivos antiguos, dependencias ni binarios.

| Archivo | Acción | Responsabilidad |
| --- | --- | --- |
| src/main/java/com/elevatormanager/dispatch/ManagerWorker.java | Nuevo | Hilo, buzón y despacho de llamadas externas. |
| src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java | Nuevo | Doce escenarios con hilos reales, ejecutables sin JUnit. |
| src/test/java/com/elevatormanager/dispatch/ManagerWorkerTest.java | Nuevo | Doce pruebas JUnit que llaman a los mismos escenarios. |
| docs/implementacion/T29-hilo-del-manejador.md | Nuevo | Contrato, alcance, integración y validación. |
| docs/comprobaciones/T29-validacion-local.md | Nuevo | Resultado de la validación realizada en este entorno. |

Extraer el contenido sobre `D:\Users\David\Desktop\workspace\java\elevator-manager`. Revisar primero si existen cambios locales no confirmados.

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T29-hilo-del-manejador.zip" -DestinationPath . -Force
```

Ajustar únicamente la ruta del ZIP si la descarga está en otra carpeta. La extracción debe crear src y docs directamente dentro del proyecto; no crear un proyecto Maven anidado.

## 3. Contrato público

El constructor recibe SimulationConfig, época de sesión, lista completa de ElevatorWorker y un LongSupplier de IDs de comando. Copia y ordena la lista por ID; exige exactamente las cabinas 1..N, con configuración y época coincidentes. Puede construirse antes de arrancarlas, pero solo despacha a trabajadores RUNNING.

| API | Contrato |
| --- | --- |
| start() | Arranca una sola vez; devuelve false en llamadas posteriores. |
| submit(HallRequest) | Encola si está RUNNING; true no significa validación ni despacho. Rechaza null inmediatamente. |
| awaitResult(Duration) | Extrae un resultado; Optional.empty al vencer el plazo. Un consumidor lógico. |
| lifecycle(), failure() | Estado del actor y causa de un fallo terminal. |
| threadName(), threadState() | Diagnóstico del hilo independiente. |
| close() | Solicita cierre local sin esperar y sin cerrar las cabinas prestadas. |
| awaitTermination(Duration) | Confirma terminación real fuera del EDT. |

Lifecycle tiene NEW, RUNNING, CLOSING, TERMINATED y FAILED. El cierre antes del arranque termina la instancia y evita arrancarla después. No se reutiliza una instancia cerrada.

Result es un record con la solicitud inmutable original, outcome, comando Pickup opcional, explicación y nombre del hilo que procesó la entrada.

| Outcome | Significado |
| --- | --- |
| DISPATCHED | ElevatorWorker.submit aceptó encolar el Pickup. Todavía puede haber un rechazo de admisión en la cabina. |
| REJECTED | Llamada inválida, época ajena o ninguna cabina disponible. El llamador conserva identidad y detalle para decidir el reintento. |
| CANCELLED | Cierre antes de despachar, o fallo del actor. Un fallo durante el envío requiere contrastar el historial de la cabina antes de reintentar. |

Los canales Result, Service y ExecutionEvent de ElevatorWorker permanecen disponibles y este manejador todavía no los consume. La confirmación de servicio sigue siendo COMMAND_COMPLETED, conforme a T27. No se publica una recogida como atendida al emitir DISPATCHED.

## 4. IDs compartidos

CommandLedger identifica comandos por época y commandId dentro de cada cabina. Los productores de Pickup y Destination deben compartir una secuencia segura entre hilos, por ejemplo una única AtomicLong de sesión y su método incrementAndGet. El constructor recibe esa función para evitar que un contador privado del manejador choque con destinos internos ya emitidos.

El generador debe ser rápido, devolver IDs positivos y no reutilizarlos. También debe compartirse entre instancias que operen sobre las mismas cabinas. No crear un contador desde cero sobre trabajadores con historial. Un valor cero o negativo produce FAILED, con causa visible y resultado para la entrada extraída.

Los productores existentes no se modifican automáticamente: al integrarlos en la sesión deberán usar esta misma secuencia. La prueba sharedCommandSequenceAvoidsCollisions verifica un destino interno con ID 1 seguido de una recogida con ID 2.

## 5. Hilos, cola y reparto provisional

submit toma el lock del buzón únicamente para insertar y señalar una Condition. El manejador duerme con Condition.await cuando no hay entradas; no sondea en un bucle ocupado. Extrae en orden FIFO bajo el lock, lo libera y luego valida, consulta snapshots y envía comandos a una cabina. No conserva su lock mientras llama a ElevatorWorker.

El orden entre productores concurrentes es el de inserción efectiva en el buzón. Solo el actor modifica el cursor de reparto. Las cabinas conservan sus propios hilos, agendas y tiempos; el manejador no espera su desplazamiento ni su parada.

Se usa reparto circular por ID para comprobar la infraestructura de T29. Desde el siguiente índice se busca una cabina RUNNING con época vigente, modo NORMAL y fase IDLE, MOVING o DWELLING. Si el envío falla porque acaba de cerrarse, se intenta la siguiente. Tras un envío exitoso avanza el cursor. Con N = 1 vuelve siempre a esa cabina.

La lectura del snapshot y el envío no constituyen una transacción: una carrera posterior puede causar rechazo en la cabina. T32 coordinará esas confirmaciones. La política circular no estima eficiencia ni sustituye la selección final por costo.

## 6. EDT y recursos

Se permiten submit, start y close desde Swing porque no esperan el procesamiento. awaitResult y awaitTermination con duración positiva lanzan IllegalStateException desde el EDT; Duration.ZERO permite sondear sin esperar. Las pruebas usan SwingUtilities.invokeAndWait y una barrera para comprobar que el EDT procesa otra acción mientras el actor está dentro del despacho.

El manejador no crea ni cierra cabinas. El futuro controlador global será su propietario. Al cerrar, un despacho ya extraído puede finalizar; todas las entradas aún en cola producen CANCELLED. Después del cierre no se aceptan nuevas entradas. Un cierre normal despierta al trabajador vacío y no requiere interrupciones.

Las colas viven en memoria y no tienen límite de capacidad, igual que los canales existentes de T27. Debe drenarse awaitResult durante la ejecución. No hay persistencia tras una caída del proceso ni garantía de memoria acotada. El generador de IDs debe respetar su contrato para que un cierre no quede retenido por código externo bloqueante.

## 7. Alcance de las siguientes tareas

| Tarea | Trabajo pendiente |
| --- | --- |
| T30 | Estimar tiempo de recogida con posición, sentido, tramo restante y paradas. |
| T31 | Seleccionar por costo y desempatar determinísticamente. |
| T32 | Estados recibido/asignado/atendido, deduplicación global, confirmaciones, rechazos y reintentos. |
| T33–T34 | Reglas de recogida y evaluación de eficiencia/escalabilidad de la política definitiva. |
| Fase 6 | Coordinación global de Start/Stop/Reset/cierre. |

T29 no deduplica llamadas compartidas entre cabinas: dos llamadas equivalentes pueden enviarse a cabinas distintas con la política provisional. No reintenta automáticamente una entrada rechazada ni introduce reset global. Esto queda registrado para evitar dar por terminada T32 anticipadamente.

## 8. Validación local

Se recuperaron los ZIP y contratos existentes y se compilaron las clases del motor, modelo, protocolo y la nueva implementación con Java 17 y --release 17. No se alteraron las clases de producción de T22–T28.

| Grupo ejecutado directamente en JVM | Escenarios satisfactorios |
| --- | ---: |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 81 |

Los doce escenarios nuevos cubren N=1 hasta servicio real, reparto con N=3 y lista invertida, ciclo de vida de un solo uso, validaciones, ausencia de cabinas, exclusión de una cabina cerrada, independencia del EDT, 48 llamadas desde cuatro productores, cierre con diez entradas pendientes, IDs compartidos, fallos visibles y contratos de construcción/estado NEW.

Se usaron barreras, colas y plazos; no sleeps ni igualdad exacta de tiempos del planificador. Maven no está disponible en este entorno: no se afirma BUILD SUCCESS ni ejecución aquí de toda la suite histórica. Los 81 corresponden a los grupos indicados, no al total Maven acumulado.

## 9. Validación en el proyecto de David

Desde la raíz Maven, ejecutar en PowerShell:

```powershell
mvn -Dtest=ManagerWorkerTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest" test
mvn clean verify
```

La primera ejecución debe informar doce pruebas nuevas sin fallos ni errores. La suite completa debe mantener las 153 pruebas reportadas al cerrar T28 y añadir estas doce: 165 esperadas si no hubo otros cambios locales. Esa cifra debe confirmarse con la salida real de Surefire.

Para conservar evidencia local:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=ManagerWorkerTest test 2>&1 | Tee-Object -FilePath .\logs\t29-hilo-del-manejador.log
```

Después de compilar, los mismos escenarios se pueden ejecutar directamente:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.ManagerWorkerScenarios
```

No se modifica la ventana de T11 en esta tarea: el actor se integra aquí con el motor mediante escenarios. La conexión de la consola definitiva seguirá el plan de interfaz.

## 10. Cierre y commit

Cerrar T29 después de verificar pruebas y BUILD SUCCESS en el proyecto de David. Entonces marcar únicamente T29 como completada en docs/01-analisis-y-plan.md y actualizar el estado de esta ficha con la evidencia real. No marcar T30–T34 como completadas.

```powershell
git status --short
git diff --check
git add -- src/main/java/com/elevatormanager/dispatch/ManagerWorker.java src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java src/test/java/com/elevatormanager/dispatch/ManagerWorkerTest.java docs/implementacion/T29-hilo-del-manejador.md docs/comprobaciones/T29-validacion-local.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T29: implementar hilo independiente del manejador"
git status --short
git log -1 --oneline
```

El commit se realiza tras la validación, con el mismo procedimiento de las tareas anteriores. No se ha creado un commit sobre el repositorio local de David desde este entorno.

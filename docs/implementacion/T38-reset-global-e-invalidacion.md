# T38 — Coordinar reset global e invalidación de mensajes

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base confirmada:** T37, commit `29538a6`, con 32 pruebas específicas, 160 seleccionadas y 329 acumuladas sin fallos, errores u omisiones; BUILD SUCCESS.
- **Estado:** implementación y escenarios locales satisfactorios; pendiente de validación Maven e incorporación en el equipo de David.

## 1. Alcance y comportamiento

SimulationController.reset() coordina el manejador y todas las cabinas de la sesión. Una operación nueva reserva una época mayor, publica RESETTING y bloquea nuevas admisiones. Solicita simultáneamente la limpieza del manejador y los retornos locales implementados en T37. Solo publica RESET después de reunir y validar todas las confirmaciones de la época y operación vigentes.

No se recrean trabajadores, suscripciones, hilos ni el generador compartido de comandos. Start desde el RESET alcanzado después de un retorno continúa los mismos actores. Si se solicita reset antes del primer Start, los actores se inician una sola vez para poder confirmar el control; quedan retenidos después, esperando Start. No se libera servicio automáticamente.

La interfaz sigue siendo la ventana mínima de T11. Los botones y formularios operativos se integran en fase 9. Los lotes preparados de importación aún no existen: T43–T46 deberán capturar y revalidar la época al incorporarlos; no se afirma haber limpiado un campo de lote todavía no implementado.

## 2. Acciones y transiciones

| Estado | Reset | Start/Stop y solicitudes |
| --- | --- | --- |
| RESET | Nueva operación con época mayor; si hace falta inicia los actores y espera sus confirmaciones. | Start arranca o continúa según exista sesión ya iniciada. |
| RUNNING | RESETTING; invalida trabajo anterior y solicita retorno. | La admisión termina al procesar el control en el coordinador. |
| STOPPING | Sustituye la operación Stop pendiente como REJECTED y solicita reset. | Confirmaciones tardías del Stop no cambian el reset. |
| STOPPED | Retorna aunque el motor estuviera detenido. | No necesita Start previo. |
| RESETTING | Se une al mismo futuro privado; no crea otra época ni otro retorno. | Start, Stop y solicitudes manuales rechazados. |
| CLOSING/CLOSED | Rechazado. | Se conserva prioridad de cierre. |

Los estados de pausa se integrarán cuando se implemente ese extra. No se publican estados de pausa ni se simula soporte de un control todavía inexistente.

## 3. Barrera completa y confirmaciones tardías

El controlador conserva el futuro privado de la operación y su época. Cada cabina recibe un Command.Reset con ID de comando propio y compartido con la secuencia del resto de productores. El manejador recibe la época objetivo por su control prioritario.

La barrera requiere:

1. Confirmación de adopción de la época por el manejador y vista de llamadas vacía, con su actor vivo.
2. Exactamente una confirmación por cada cabina esperada: ID correcto y época objetivo.
3. Piso 1, dirección UP, fase IDLE, modo NORMAL, sin siguiente piso, compromiso ni solicitudes internas o externas; trabajador vivo.
4. Operación y época todavía vigentes en el coordinador, sin cierre que las haya sustituido.

Se usan futuros y callbacks que vuelven al ejecutor del controlador. No se espera un viaje ni un join bajo un bloqueo global o en EDT. getNow se utiliza únicamente para leer confirmaciones ya completadas en la barrera; no hace espera.

Una sola cabina en 1/UP no basta. Un snapshot de pantalla no sustituye su futuro de confirmación. Una confirmación tardía de Stop o de otro reset no completa una operación diferente: se comprueban estado, época e identidad del futuro privado. Cancelar el futuro visible del consumidor no cancela esa operación privada.

Si un participante falla se informa la incidencia y se solicita cierre, sin publicar RESET. La ruta de fallo es temprana: no espera a que otra cabina termine un recorrido largo. Si llega close() durante el retorno, el reset pendiente devuelve REJECTED por cierre y no queda sin resolver. La supervisión de cierre y recursos de logging se completará en T39.

## 4. Limpieza del manejador

ManagerWorker conserva su hilo y cambia de registro al adoptar la nueva época. Su control de reset tiene prioridad sobre Stop y despacho nuevo, pero una operación de despacho ya extraída termina antes de confirmar la adopción. Los comandos anteriores que alcanzaran una cabina antes de su reset local se cancelan allí; después de adoptarlo, el canal y la época impiden que se incorporen de nuevo.

El actor del manejador:

- Cancela las entradas que permanecían en su inbox y produce un resultado CANCELLED por entrada.
- Invalida el seguimiento abierto, incluidos alias, mediante HallRequestLedger.cancelForReset().
- Publica las actualizaciones finales del registro anterior y lo sustituye por uno vacío de la nueva época.
- Reinicia el cursor de reintentos, conserva la secuencia compartida de comandos y queda detenido hasta resume().
- Confirma únicamente después de esa limpieza. El controlador espera además todos los retornos físicos.

SERVED ya confirmado no se convierte en cancelado. Cancelar seguimiento todavía abierto no deshace un servicio que físicamente pudiera haberse producido y cuya notificación siguiera en vuelo. Sus hechos históricos conservan su época y no se utilizan para dar servicio a la generación nueva.

El mailbox, los resultados y las actualizaciones históricos no se borran para fingir que nunca existieron. Al continuar, el registro nuevo ignora eventos de época anterior antes de modificar sus secuencias o estados. Los consumidores de awaitResult/awaitUpdate del manejador deben comprobar request.getEpoch(). Su threadName identifica el hilo creado inicialmente, no la época actual; para esta última se usa epoch().

Una repetición de la misma época ya adoptada devuelve confirmación histórica sin vaciar de nuevo trabajo posterior ni detener otra vez al manejador. El controlador global evita confundir ese replay con una operación nueva: siempre incrementa época salvo que ya esté RESETTING.

## 5. Publicación y filtrado de mensajes

SimulationController.Result incorpora el campo epoch, también para resultados de solicitudes externas que no contienen Command.Destination. isCurrent(Result) permite reconocer respuestas obtenidas antes de un reset. Las creaciones internas de ese record se actualizan; sus accesores previos se conservan.

awaitEvent(timeout) del controlador entrega eventos de la época vigente y descarta los anteriores. También comprueba la época del comando si existe: un rechazo de un comando viejo capturado con un snapshot nuevo no vuelve vigente ese comando. Los eventos físicos sin comando siguen siendo válidos por su snapshot.

isCurrent(ExecutionEvent) debe revalidarse al aplicar un evento que ya se había extraído antes de un reset concurrente. El filtrado en la cola no puede retirar de la mano del consumidor un objeto inmutable que recibió previamente. Lo mismo aplica a respuestas, llamadas y snapshots conservados en la vista.

calls() filtra por la época activa, incluso mientras el actor del manejador todavía está adoptando el control. Tras el futuro de reset, el registro completo del manejador y las agendas de las N cabinas están vacíos. snapshots() sigue mostrando todas las cabinas: durante RESETTING puede haber épocas distintas, que representan participantes aún pendientes de adopción y no trabajo vigente para ejecutar.

Los getters independientes no equivalen a una fotografía global simultánea. Al aplicar una proyección visual, comprobar su época vigente. RESET solo se publica después de la barrera, por lo que nunca significa que falte un retorno conocido.

El timeout positivo de awaitEvent se consume entre lecturas de eventos descartados y no se reinicia. El sondeo ZERO descarta como máximo 256 eventos históricos por llamada para no drenar un historial enorme en una sola ejecución EDT; puede devolver empty mientras queden elementos por depurar. La siguiente consulta continúa. Los canales propios de las cabinas conservan sus hechos para consumidores históricos independientes; la alimentación actual del controlador no sustituye ese registro completo.

## 6. Start posterior y límites numéricos

El controlador distingue el primer arranque de la continuación de una sesión ya creada. Tras reset, resume las N cabinas y el manejador existentes. Conserva IDs de cabina, hilos y la secuencia de comandos; solo cambia la época. Un UUID de solicitud puede utilizarse en la nueva época sin heredar el resultado anterior, conforme a T37 y al registro nuevo del manejador.

La época se calcula con Math.incrementExact antes de producir efectos. Si no hay época o IDs representables, reset devuelve REJECTED y solicita al usuario cerrar la sesión; no vuelve a valores negativos ni inicia trabajadores parcialmente por ese agotamiento. Las duraciones de prueba no son un límite de duración válido del motor: un get con timeout no cancela por sí solo la operación ni autoriza a publicar RESET.

## 7. Pruebas de T38

GlobalResetScenarios y GlobalResetTest ejercitan los mismos 33 escenarios: reset inicial; espera por la cabina más lenta; retorno desde STOPPED y sustitución de STOPPING; cancelación de parada abierta; 32 productores que solicitan el mismo reset; operación posterior en otra época; rechazo de controles durante retorno; identificación de respuestas y eventos antiguos; depuración de más de 256 eventos históricos; rechazo de solicitudes viejas; reutilización de UUID y crecimiento de IDs; N=1/P=1; agotamiento de época; cierre durante y después del retorno; respuesta de EDT; 200 ciclos Reset/Start/Stop; cancelación del futuro visible; ausencia de trabajo espontáneo al continuar; preservación de historial SERVED; eventos viejos de secuencia muy grande sin contaminar la época nueva; identidades antiguas no reservadas; limpieza de inbox del manejador detenido; despacho en vuelo; finalizaciones antiguas en mailbox; rechazo recuperable de reset inválido; replay sin limpiar trabajo nuevo; manejador cerrado; rechazo de nuevas entradas con época vieja; y snapshot nuevo con comando antiguo rechazado por isCurrent.

Se usan latches, futuros, eventos y plazos de diez segundos, sin sleeps para deducir estados. El generador bloqueado de un escenario es una herramienta controlada de prueba; el generador de producción sigue siendo rápido y compartido. Los 200 ciclos y los 32 productores son iteraciones internas y no se cuentan como cientos de nuevos tests JUnit.

## 8. Archivos completos

| Ruta | Cambio |
| --- | --- |
| src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java | Cancelación de seguimiento abierto antes de sustituir registro. |
| src/main/java/com/elevatormanager/dispatch/ManagerWorker.java | Control prioritario de reset, época mutable y limpieza del actor detenido. |
| src/main/java/com/elevatormanager/simulation/SimulationController.java | Barrera global, continuación y filtrado por época. |
| src/test/java/com/elevatormanager/simulation/GlobalResetScenarios.java | 33 escenarios independientes. |
| src/test/java/com/elevatormanager/simulation/GlobalResetTest.java | Los mismos casos como pruebas JUnit. |
| docs/implementacion/T38-reset-global-e-invalidacion.md | Esta ficha. |
| docs/implementacion/T38-codigo-completo.md | Las cinco clases completas para lectura. |
| docs/comprobaciones/T38-validacion-local.md | Comprobaciones reales y sus límites. |

Se actualizan el plan y las dos fichas de T37 con su cierre confirmado por David. No cambian POM, dependencias, cabinas de T37 ni la ventana mínima. El ZIP contiene el proyecto completo con rutas desde la raíz Maven, sin .git, target ni logs temporales.

## 9. Incorporación y validación PowerShell

Desde el árbol limpio de T37, ajustar la ruta de descarga si corresponde:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T38-reset-global-e-invalidacion.zip" -DestinationPath . -Force
mvn -Dtest=GlobalResetTest test
mvn "-Dtest=GlobalResetTest,CabinResetTest,SimulationControllerTest,StopContinuationTest,ManagerWorkerTest,PendingConfirmationTest,ExecutionEventTest" test
mvn clean verify
```

Se esperan 33 pruebas específicas, 158 en el grupo indicado y 362 acumuladas sobre las 329 de T37. Confirmar los números reales y BUILD SUCCESS. Para ejecutar los mismos escenarios independientes después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.simulation.GlobalResetScenarios
```

Salida esperada: `T38: 33 escenarios satisfactorios.` La barrera se verifica sobre actores reales; la demostración mediante botones de la GUI llegará en su fase correspondiente.

## 10. Commit sugerido y cierre

Después de validar Maven, marcar T38 completada en el plan y actualizar el estado de esta ficha. Preparar los archivos explícitos:

```powershell
$archivosT38 = @(
    'src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java'
    'src/main/java/com/elevatormanager/dispatch/ManagerWorker.java'
    'src/main/java/com/elevatormanager/simulation/SimulationController.java'
    'src/test/java/com/elevatormanager/simulation/GlobalResetScenarios.java'
    'src/test/java/com/elevatormanager/simulation/GlobalResetTest.java'
    'docs/01-analisis-y-plan.md'
    'docs/implementacion/T38-reset-global-e-invalidacion.md'
    'docs/implementacion/T38-codigo-completo.md'
    'docs/comprobaciones/T38-validacion-local.md'
    'docs/implementacion/T37-reset-prioritario-cabina.md'
    'docs/comprobaciones/T37-validacion-local.md'
)
git diff --check
git add -- $archivosT38
git diff --cached --check
git diff --cached --stat
git commit -m "T38: coordinar reset global e invalidacion de mensajes"
git status --short
```

**Siguiente tarea:** T39 — Implementar cierre ordenado. T39–T40 siguen abiertas; la fase 6 aún no termina.

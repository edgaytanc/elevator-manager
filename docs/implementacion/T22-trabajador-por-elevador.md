# T22 — Implementar un trabajador por elevador

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-21, Guatemala.
- **Versión:** 0.1.0.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Estado:** Implementada; siete escenarios ejecutados; Terminada integración y regresión Maven en el proyecto de David.

## 1. Continuidad recuperada

Se consultó contexto de «Plan Kanban Java», «Iniciar T10 del proyecto» e «Iniciar T16 Configuración Inmutable», además del enunciado adjunto y los archivos existentes del modelo.

Las fases 1, 2 y 3 están cerradas según David. El historial recuperado registra T21 con siete pruebas específicas y 52 acumuladas, sin fallos, errores ni omitidas. Ese resultado corresponde a la fase anterior; no constituye una ejecución nueva en este entorno.

Se conserva Java 17, Maven, JUnit, Swing y el paquete raíz `com.elevatormanager`. La entrada existente sigue siendo `com.elevatormanager.app.ElevatorManager`. La herencia real permanece en `Request`, `HallRequest` y `CabinRequest`.

La forma de trabajo continúa siendo una tarjeta por vez: implementación completa, archivos descargables, instrucciones de incorporación, validación y cierre posterior. La documentación interna se entrega en Markdown.

## 2. Objetivo y límite de esta tarjeta

T22 exige un trabajador identificable por cabina, buzón independiente y espera bloqueada sin sondeo continuo. Cada instancia es dueña de un `ElevatorState` privado creado con ID, configuración compartida y época inicial.

| Tarjeta | Responsabilidad |
| --- | --- |
| T22 | Hilo, buzón, incorporación de comandos a agenda y publicación segura. |
| T23 | Desplazamiento temporizado entre pisos adyacentes. |
| T24 | Parada y atención de solicitudes compatibles. |
| T25 | Orden de recorrido por sentido y frontera comprometida. |
| T26 | Inserción durante movimiento/parada y deduplicación de ejecución. |
| T27 | Eventos completos de ejecución y servicio. |
| T28 | Verificación de recorridos simultáneos y tiempos. |

En T22 una solicitud aceptada queda pendiente en la agenda, con piso 1 y fase IDLE. Todavía no se mueve ni se atiende. `Command.Reset` devuelve rechazo explícito porque su adopción de época y retorno físico se implementan en fase 6.

## 3. Archivos y ubicación

| Archivo | Ruta desde la raíz Maven |
| --- | --- |
| ElevatorWorker.java | `src/main/java/com/elevatormanager/simulation/ElevatorWorker.java` |
| ElevatorWorkerScenarios.java | `src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java` |
| ElevatorWorkerTest.java | `src/test/java/com/elevatormanager/simulation/ElevatorWorkerTest.java` |
| Este documento | `docs/implementacion/T22-trabajador-por-elevador.md` |

Son archivos nuevos. No reemplazar las clases del modelo ni el POM. No conectar todavía estos trabajadores al arranque de la ventana: la composición y autorización global corresponden al controlador posterior.

## 4. Contrato de concurrencia

- El constructor crea el estado y su snapshot inicial antes de compartir la instancia; después solo el hilo de esa cabina muta su estado.
- `start()` crea ejecución una sola vez y devuelve false para llamadas repetidas o tras cierre.
- El hilo se llama `elevator-worker-ID`; la composición futura debe crear exactamente una instancia por ID de sesión.
- `submit()` solo encola si el trabajador está RUNNING. true significa encolado, no aceptación ni servicio.
- El consumidor espera en `Condition.await()` dentro de un while que comprueba buzón y ciclo local.
- Productor y consumidor usan el mismo lock para predicado, inserción y señal; no hay señales perdidas.
- El lock pertenece exclusivamente a esa cabina. No se conserva durante esperas remotas, I/O ni callbacks.
- El procesamiento local de admisión es breve y se serializa con cierre bajo ese lock.
- `snapshot()` lee una `AtomicReference<ElevatorSnapshot>`; no expone el estado mutable.
- Los resultados se entregan por una cola independiente, después de liberar el lock. Tendrá un único consumidor lógico; no se deben repartir lectores que compitan por resultados.
- `awaitResult()` y `awaitTermination()` son operaciones para pruebas o supervisión fuera del EDT. El canal de resultados no ejecuta callbacks arbitrarios.

Los buzones son no acotados en esta primera implementación. El consumidor futuro debe drenar resultados; el visor gráfico no debe reutilizar estas colas como historial ilimitado.

## 5. Ciclo local y controles futuros

`Lifecycle` describe el hilo, no sustituye a `SimulationState` ni a `ElevatorPhase`. RUNNING aquí significa que el trabajador consume mensajes; la autorización global de solicitudes manuales seguirá perteneciendo al controlador.

`close()` es cierre técnico local, no Stop ni reset del simulador. Tiene prioridad sobre entradas todavía no extraídas: las rechaza con un resultado por comando. Una admisión ya completada conserva su resultado de aceptación. El snapshot conserva el último estado publicado para inspección; este esqueleto no lo transforma en un snapshot físico TERMINATED ni afirma que las solicitudes fueron atendidas. El cierre global de fase 6 deberá completar esa transición y coordinar cancelaciones pendientes.

`awaitTermination()` usa join con plazo y sin lock de buzón. true acredita que el hilo no está vivo. Un trabajador NEW devuelve false hasta arrancar o cerrarse. No se reinicia una instancia terminada.

Una interrupción inesperada marca FAILED y conserva la interrupción. Los errores fatales quedan disponibles en `failure()`; no se reanuda una cabina fallida automáticamente. Si una excepción fatal ocurre tras extraer un comando, su resultado puede ser incierto y el supervisor debe tratarlo como fallo, no como servicio confirmado. Las entradas aún encoladas reciben rechazo al finalizar.

Se conserva el rechazo funcional de duplicados de `ElevatorState` y su repetición exacta de UUID pendiente. No se incorpora todavía el registro completo de idempotencia por commandId de ejecución.

## 6. Verificación ejecutada

Se compilaron con Java 17 las fuentes nuevas de producción y escenarios junto con diez clases recuperadas del modelo/protocolo y `ValidationError`. Se ejecutó `ElevatorWorkerScenarios.main` con resultado:

```text
T22: 7 escenarios satisfactorios.
```

| Escenario | Comprobación |
| --- | --- |
| initialState | Constructor sin arrancar hilo; piso 1, UP, IDLE y revisión cero. |
| blockingAndWakeup | Espera real WAITING, señal al encolar y publicación de agenda. |
| independentWorkers | Dos hilos con productores concurrentes y 100 solicitudes por cabina, sin mezclar agendas. |
| rejectionDoesNotKillWorker | Destinatario incorrecto, reset no implementado y época incorrecta se rechazan; luego admite trabajo válido. |
| immutablePublication | Snapshot anterior intacto, revisión creciente y colección inmodificable. |
| closeAccountsForQueuedCommands | Un resultado por entrada, fin real del hilo y rechazo de nuevos envíos. |
| singleStartAndCloseBeforeStart | Arranque único, cierre idempotente y cierre antes de arrancar. |

La prueba de espera observa el estado del hilo dentro de un plazo; su pausa mínima de observación pertenece solo al diagnóstico de prueba. El trabajador de producción no tiene sondeo. Los productores usan un latch de inicio, no sleeps para ordenar los envíos.

Esta verificación acredita infraestructura concurrente y agendas independientes. La demostración temporal de cabinas físicamente ocupadas y recorridos solapados se completa en T23–T28.

Maven no está disponible en este entorno. El adaptador JUnit se entrega para ejecutar en el proyecto existente; aquí no se ejecutaron las 52 pruebas anteriores ni se generó el JAR integrado. No se declara `mvn clean verify` exitoso sin su salida real.

## 7. Integración y validación en PowerShell

Copiar cada archivo a la ruta de la sección 3. Desde la raíz del proyecto:

```powershell
mvn -Dtest=ElevatorWorkerTest test
mvn clean verify
git status --short
git diff --check
```

Resultado esperado: siete pruebas nuevas y, si el proyecto conserva exactamente las 52 pruebas anteriores, 59 acumuladas, sin fallos, errores ni omitidas. La ventana de T11 conserva su comportamiento, ya que estos workers todavía no se conectan a su composición.

Tras revisar los resultados y el contenido incorporado:

```powershell
git add -- src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java src/test/java/com/elevatormanager/simulation/ElevatorWorkerTest.java docs/implementacion/T22-trabajador-por-elevador.md
git diff --cached --check
git diff --cached --stat
git commit -m "T22: implementar trabajador independiente por elevador"
git status --short
```

Actualizar este documento con el resultado real de la integración y mover T22 a Terminada tras comprobarlo. Próxima tarjeta: **T23 — Implementar desplazamiento entre pisos**.

## 8. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Infraestructura de trabajador, admisión, publicación segura, cierre local y siete escenarios. |

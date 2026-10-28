# T39 — Implementar cierre ordenado

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base confirmada:** T38, commit `f4b654c`; 33 pruebas específicas, 158 seleccionadas y 362 acumuladas sin fallos, errores u omisiones; BUILD SUCCESS y árbol limpio confirmados por David.
- **Estado:** Maven e incorporación confirmados por David: 32 pruebas específicas, 164 seleccionadas y 394 acumuladas sin fallos, errores u omisiones, BUILD SUCCESS y commit `74d467d`, con árbol limpio. La comprobación visual de Cerrar/X sigue pendiente de confirmación.

## 1. Cierre implementado

closeAsync() solicita cierre y devuelve CompletableFuture<Result>. close() conserva AutoCloseable y delega en esa operación, sin esperar en el hilo llamador. El controlador completa el proceso automáticamente: ya no necesita que un usuario llame awaitTermination para impulsar o publicar CLOSED.

La solicitud es idempotente y prioritaria. Desde que se solicita, se rechazan nuevas acciones normales y recursos. Las acciones que seguían en la cola del coordinador también se rechazan al ejecutarse, en lugar de iniciar otro viaje, reset o sesión después de pedir cierre.

El coordinador publica CLOSING, resuelve Stop y reset pendientes como REJECTED por cierre, solicita terminación cooperativa al manejador y a las N cabinas y deja de admitir tareas. Los controles existentes despiertan esperas de movimiento, parada y detención; no se espera la duración completa de un tramo ni se termina artificialmente un recorrido de servicio.

Un hilo independiente simulation-shutdown espera la terminación real del coordinador y los actores. Solo después libera los recursos registrados en orden inverso de adquisición. Si termina correctamente, publica CLOSED y completa la confirmación. No se usa System.exit, Thread.stop, suspensión forzada ni join en EDT.

## 2. Recursos y propiedad

registerResource(AutoCloseable) agrega un recurso a la sesión de forma serializada:

| Resultado | Propiedad |
| --- | --- |
| APPLIED | El controlador asume su cierre. |
| UNCHANGED | La misma identidad ya estaba registrada; no se añade dos veces. |
| REJECTED | El recurso sigue siendo del llamador, que debe liberarlo. |

La deduplicación es por identidad, no por equals. Se permite registrar recursos antes o durante una sesión abierta. Los recursos no se cierran mientras los actores todavía puedan producir trabajo sobre ellos. Tras terminar el coordinador, la propiedad de su lista pasa al hilo de cierre, sin escritores concurrentes.

Las excepciones habituales de cierre se agregan y se siguen intentando los demás recursos. Se conservan sus causas como suppressed. Si no se confirma la liberación de todos, el futuro falla, failure() conserva la incidencia y el estado permanece CLOSING: no se anuncia CLOSED como si todo hubiera salido bien. Los errores inesperados también se notifican; no se ocultan.

La operación privada de cierre se ejecuta una sola vez. Repetir closeAsync devuelve otra vista de la misma confirmación; no vuelve a ejecutar recursos que ya fallaron. No es una recuperación automática de recursos externos defectuosos. Cancelar el futuro visible tampoco cancela la terminación privada de trabajadores ni libera recursos prematuramente.

Los callbacks de recursos deben realizar su propia liberación y no esperar el cierre de la sesión que los está cerrando. Una espera positiva mediante awaitTermination desde el coordinador o el hilo de cierre se rechaza para evitar autoespera. La interrupción recibida por el cerrador durante los joins se conserva y se restaura al finalizar su limpieza.

## 3. Logging propio y ajeno

OwnedLogHandlers adapta java.util.logging.Handler a AutoCloseable. Recibe un Logger y una lista copiada de handlers que pertenecen a la sesión. No los crea, configura ni agrega automáticamente.

Al cerrar, desconecta cada handler propio, llama flush y close, e intenta completar los demás aunque uno lance una excepción de ejecución. Es idempotente y conserva el diagnóstico si falló. Rechaza listas con una misma identidad repetida para no asumir dos veces el mismo recurso.

No cierra handlers ajenos, de loggers padre ni de la consola global de la JVM. La configuración, formato y consumo de eventos de logging por cabina siguen correspondiendo a T41–T42. Esa integración podrá registrar el conjunto de handlers como recurso de sesión; si añade consumidores de eventos, deberá ordenarlos para terminar y drenar antes de cerrar sus handlers. El diagnóstico aislado T09 mantiene su ciclo de vida propio.

Se prueba un FileHandler real: escribir, cerrar desde la sesión, comprobar la ausencia de su archivo .lck, renombrar el log y reabrir el mismo destino sin crear otro por bloqueo. Las pruebas generan archivos temporales y los eliminan al terminar.

## 4. Espera acotada y estados

awaitTermination(timeout) observa la terminación, sin impulsarla ni modificar estados:

- Devuelve true solo tras cierre exitoso y salida real del hilo cerrador.
- Devuelve false si no se solicitó cierre, vence el plazo o falló la liberación de recursos.
- Un timeout no cancela el cierre pendiente ni publica CLOSED. Puede esperarse de nuevo después.
- Una espera positiva se rechaza en EDT y en hilos que deben completar ese cierre. ZERO permite consulta inmediata.

CLOSED significa que terminaron los productores de la sesión y se liberaron los recursos registrados. El hilo cerrador lo publica al finalizar esos pasos y completa el futuro; awaitTermination confirma además que ese hilo ya salió. Los snapshots históricos no se reinterpretan como servicios completados por cerrar. No se exige vaciar todos los canales de hechos para fingir que nunca existió trabajo anterior.

No se fuerza un plazo arbitrario del motor para cerrar un recurso externo. Si un recurso tarda, el futuro permanece pendiente y la GUI responde mientras muestra cierre en curso. Si falla, la incidencia es explícita. El cierre integrado no convierte un fallo previo del motor en trabajo exitoso; su diagnóstico permanece disponible.

## 5. Integración con la ventana

WindowCloseHandler centraliza botón y X. Su estado y callbacks visuales pertenecen exclusivamente al EDT. Solicita una sola operación mientras está cerrando y programa la disposición mediante invokeLater cuando recibe confirmación. Si falla, informa a la vista y no la dispone como si los recursos se hubieran liberado.

MainWindow utiliza DO_NOTHING_ON_CLOSE. El botón Cerrar y windowClosing llaman al mismo manejador. Durante la espera se deshabilita el botón y se muestra el estado de cierre. Ante fallo se conserva la ventana, se informa por Logger y vuelve a habilitar el botón para reportar otra solicitud. El proveedor de cierre decide si un nuevo intento puede recuperarse; el controlador conserva la misma operación privada y su error, sin repetir automáticamente recursos fallidos.

El constructor MainWindow(Supplier<? extends CompletionStage<?>>) permite conectar una sesión con controller::closeAsync. Su proveedor debe ser no bloqueante y completar correctamente solo cuando el cierre esté confirmado.

La entrada mínima actual todavía no configura ni crea una sesión de simulación: eso corresponde a T47 y a la GUI de fase 9. Su constructor sin argumentos usa una confirmación inmediata porque no tiene actores ni recursos de sesión que liberar. La integración con un controlador real, recursos bloqueados y callbacks EDT sí se prueba sin JFrame mediante WindowCloseHandler. No se introduce una configuración fija de prueba en la aplicación para fingir que ya existen los formularios de la fase 9.

## 6. Pruebas de T39

OrderlyShutdownScenarios y OrderlyShutdownTest contienen los mismos 32 escenarios. Cubren cierre sin arranque, actores libres, movimiento y parada de un minuto, Stop/RESETTING/RESET, 64 solicitudes concurrentes, cancelación del futuro visible, orden inverso de recursos, registro duplicado y rechazado, liberación después de los actores, recursos bloqueados y fallidos, conservación de múltiples causas, timeout sin cancelación, protección del EDT y de autoesperas, rechazo inmediato de acciones nuevas, validación de argumentos, callbacks visuales en EDT, disposición solo después de liberar recursos, fallo visual sin disposición, validación del manejador visual, handlers propios y ajenos, fallos de handlers, FileHandler real y referencias de hilos que ya no están vivos después de confirmar terminación.

Los latches bloqueados son recursos controlados de prueba, liberados expresamente. Los plazos son de diez segundos y no se usan sleeps para inferir estado. El texto final satisfactorio es `T39: 32 escenarios satisfactorios.` Los bucles de productores son iteraciones internas, no nuevas pruebas JUnit independientes.

## 7. Archivos completos

| Ruta | Cambio |
| --- | --- |
| src/main/java/com/elevatormanager/simulation/SimulationController.java | Cierre automático, recursos propios, confirmación y espera acotada. |
| src/main/java/com/elevatormanager/logging/OwnedLogHandlers.java | Liberación de handlers de sesión sin tocar otros. |
| src/main/java/com/elevatormanager/ui/WindowCloseHandler.java | Puente de cierre asíncrono y callbacks EDT. |
| src/main/java/com/elevatormanager/ui/MainWindow.java | Botón y X unidos a confirmación antes de disponer. |
| src/test/java/com/elevatormanager/simulation/OrderlyShutdownScenarios.java | 32 escenarios independientes. |
| src/test/java/com/elevatormanager/simulation/OrderlyShutdownTest.java | Los mismos 32 adaptadores JUnit. |
| docs/implementacion/T39-cierre-ordenado.md | Esta ficha. |
| docs/implementacion/T39-codigo-completo.md | Las seis clases completas para lectura. |
| docs/comprobaciones/T39-validacion-local.md | Resultados reales y sus límites. |

Se actualizan el plan y las dos fichas de T38 con el cierre confirmado por David. No cambian POM, dependencias ni la clase de entrada. El ZIP contiene el proyecto completo desde la raíz Maven, sin .git, target ni logs temporales.

## 8. Incorporación, Maven y comprobación visual

Desde el árbol limpio de T38, ajustar la ruta de descarga si corresponde:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T39-cierre-ordenado.zip" -DestinationPath . -Force
mvn -Dtest=OrderlyShutdownTest test
mvn "-Dtest=OrderlyShutdownTest,GlobalResetTest,CabinResetTest,StopContinuationTest,SimulationControllerTest,ManagerWorkerTest,ExecutionEventTest" test
mvn clean verify
```

Se esperan 32 pruebas específicas, 164 seleccionadas y 394 acumuladas sobre las 362 de T38. Confirmar cifras reales y BUILD SUCCESS. Para los mismos escenarios independientes después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.simulation.OrderlyShutdownScenarios
```

Comprobar también la ventana actual fuera del IDE:

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Primera ejecución: pulsar Cerrar y comprobar disposición de la ventana y retorno al prompt. Segunda ejecución: cerrar mediante la X y comprobar el mismo resultado. No debe ser necesario terminar Java desde el administrador de tareas. La ventana mínima no crea aún una sesión; estas dos comprobaciones verifican su cableado visual actual. La terminación de motor y recursos está cubierta por los escenarios reales del controlador y su integración con WindowCloseHandler.

## 9. Commit sugerido y cierre

Después de Maven y la comprobación visual, marcar T39 completada en el plan y actualizar esta ficha:

```powershell
$archivosT39 = @(
    'src/main/java/com/elevatormanager/simulation/SimulationController.java'
    'src/main/java/com/elevatormanager/logging/OwnedLogHandlers.java'
    'src/main/java/com/elevatormanager/ui/WindowCloseHandler.java'
    'src/main/java/com/elevatormanager/ui/MainWindow.java'
    'src/test/java/com/elevatormanager/simulation/OrderlyShutdownScenarios.java'
    'src/test/java/com/elevatormanager/simulation/OrderlyShutdownTest.java'
    'docs/01-analisis-y-plan.md'
    'docs/implementacion/T39-cierre-ordenado.md'
    'docs/implementacion/T39-codigo-completo.md'
    'docs/comprobaciones/T39-validacion-local.md'
    'docs/implementacion/T38-reset-global-e-invalidacion.md'
    'docs/comprobaciones/T38-validacion-local.md'
)
git diff --check
git add -- $archivosT39
git diff --cached --check
git diff --cached --stat
git commit -m "T39: implementar cierre ordenado de la simulacion"
git status --short
```

**Siguiente tarea:** T40 — Probar carreras del ciclo de vida. La fase 6 permanece abierta hasta completar esa tarea.

## Confirmación posterior a la entrega

El 2026-10-28 David compartió la validación Maven completa y el commit `74d467d`, con git diff --check, git diff --cached --check y git status --short limpios. No se recibió aún confirmación de la revisión manual de Cerrar y X; las instrucciones históricas anteriores no representan una comprobación visual realizada. T40 agrega las carreras de integración del ciclo de vida.

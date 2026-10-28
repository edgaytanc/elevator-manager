# T37 — Implementar reset prioritario por cabina

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-27, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base confirmada:** T36, commit `1f9b0c0`; 21 pruebas específicas, 128 seleccionadas y 297 acumuladas sin fallos, errores u omisiones; BUILD SUCCESS y árbol limpio aportados por David.
- **Estado:** implementación y escenarios locales satisfactorios; pendiente de validación Maven e incorporación en el equipo de David.

## 1. Alcance de T37

El trabajador de cada cabina procesa un reset prioritario, invalida el trabajo anterior y vuelve físicamente a piso 1. La dirección final es UP, sin agenda y esperando una continuación explícita. Se conserva el mismo hilo y la secuencia de eventos; no se reconstruye el trabajador.

La operación es local. No se agrega todavía SimulationController.reset(), no se reinicia el manejador, no se publica RESET global ni se confirma una barrera de N cabinas. Ese trabajo corresponde a T38. La interfaz gráfica sigue siendo la ventana mínima de T11; sus controles operativos se implementan en fase 9.

La aplicación futura deberá usar el controlador global; no sustituir un reset de toda la simulación por llamadas locales aisladas. En particular, el registro del manejador actual conserva su propia época y T38 coordinará su invalidación. Esta entrega verifica trabajadores independientes y la frontera de control necesaria para esa coordinación.

## 2. Semántica física

| Situación al adoptar el reset | Comportamiento |
| --- | --- |
| IDLE en piso 1 | Limpia, adopta época y confirma inmediatamente 1/UP. No inventa un viaje. |
| IDLE en otro piso | Retorna a 1 mediante tramos adyacentes con el tiempo configurado. |
| MOVING | Conserva próximo piso, sentido y comienzo temporal del tramo actual. Lo termina y después inicia el retorno a 1. |
| DWELLING | Descarta la parada anterior y su restante. No espera el tiempo de pasajeros. |
| HELD por Stop durante parada | Descarta la suspensión y retorna; no requiere reanudar primero. |
| Stop confirmado o pendiente | El reset tiene prioridad. Una confirmación Stop todavía pendiente al adoptar reset se termina excepcionalmente como sustituida. |
| Cierre | Cierre tiene prioridad. Un retorno interrumpido no produce RESET_COMPLETED ni éxito en su futuro. |

Durante retorno no se abren paradas de servicio y no se emite COMMAND_COMPLETED por trabajo cancelado. ARRIVED sigue representando una llegada física, no una recogida. El último piso lógico solo cambia cuando se completa un tramo. Al llegar a 1 se restablece UP, incluso si la cabina ya estaba en 1 con dirección DOWN.

El snapshot publica Mode.RESET_RETURN durante la operación y no contiene solicitudes. La confirmación final contiene IDLE, piso 1, UP, sin siguiente piso, sin compromiso y Mode.NORMAL. La retención posterior es un control del trabajador, no una parada de servicio ni un HELD/MOVING ficticio.

## 3. API y resultados

| Operación | Contrato |
| --- | --- |
| requestReset(Command.Reset) | Encola control prioritario y devuelve CompletableFuture<ElevatorSnapshot>. El futuro confirma el retorno físico, no solo la admisión. |
| submit(Command.Reset) | Usa el mismo canal prioritario; true confirma encolado. Consultar Result y RESET_COMPLETED para admisión y final físico. |
| submitIfCurrent(Command.Reset, snapshot) | Revalida snapshot y encola en el canal prioritario. STALE no produce efectos. |
| resume() tras reset completado | Habilita el mismo actor y su servicio en la nueva época. No crea un hilo nuevo. |
| submit/submitIfCurrent de servicio durante reset o retención final | Rechaza el envío. No lo prepara para ejecutar accidentalmente después. |
| requestStop() o resume() durante retorno | Stop devuelve un futuro fallido y resume devuelve false; no congelan el retorno prioritario. |

Los controles exigen un trabajador iniciado y disponible. Un reset nuevo requiere una época estrictamente mayor que la actual y destinatario válido. Un rechazo no mata al trabajador ni adopta la época. El comando de reset no se admite como una solicitud de pasajeros en CommandLedger: el actor lo enruta al canal prioritario.

La repetición exacta del último reset comparte su confirmación y emite una respuesta de replay sin reiniciar el tramo, limpiar de nuevo ni cambiar otra vez la época. También después de terminar devuelve la confirmación histórica, sin regresar una cabina que ya haya continuado. Su snapshot histórico debe interpretarse con identidad y época; no reemplaza una lectura actual del estado.

Una operación distinta mientras hay retorno activo se rechaza, incluso si propone una época mayor. T38 serializará la única operación global vigente. Al finalizar sí puede iniciarse otra operación local con una nueva época. Un reset antiguo que ya no coincide con el último control se rechaza por época.

## 4. Invalidación local y cancelaciones

Al adoptar reset, únicamente el actor modifica sus estructuras:

1. Obtiene los comandos admitidos que aún esperaban servicio y limpia el registro de esa época.
2. Emite COMMAND_CANCELLED para cada comando pendiente, incluidos los alias funcionales, conservando comando, UUID canónico y snapshot de época anterior.
3. Descarta el inbox de servicio y devuelve un Result.REJECTED por cada orden que había sido encolada pero no admitida.
4. Limpia agenda interna, agenda externa y barrido, adopta la nueva época y publica RESET_STARTED.
5. Ejecuta el retorno físico y publica RESET_COMPLETED solo en 1/UP.

Un comando admitido recibe su cancelación por evento; no recibe un segundo resultado de admisión. Las órdenes rechazadas antes de encolarse no generan un resultado posterior. Un servicio ya realizado conserva sus eventos históricos y no se transforma en cancelado ni se repite.

Los canales de resultados, servicios y eventos no se vacían como si los hechos anteriores nunca hubieran ocurrido. Los consumidores deben correlacionar época y operación. T38 impedirá que eventos anteriores afecten al nuevo estado global.

Después de continuar, un comando de época antigua se rechaza antes de insertar pendientes. Las identidades de servicio pueden reutilizarse en una época nueva porque el registro anterior se libera. El ID del último reset queda reservado en su época y no puede usarse como un comando de servicio diferente. La secuencia compartida de IDs del controlador sigue siendo la regla de producción.

## 5. Concurrencia y prioridades

El orden de atención es cierre > reset > Stop > servicio. El inbox prioritario pertenece al trabajador y se procesa antes de transiciones normales o admisiones. Las entradas de servicio se bloquean desde que hay un reset encolado hasta su final y continuación explícita. Esto también protege el envío condicionado sobre un snapshot anterior todavía publicado.

Las mutaciones ocurren bajo el lock local y solo el actor cambia el modelo. Las confirmaciones, cancelaciones de futuros y resultados se entregan después de liberar ese lock y publicar el lote de eventos. El futuro entregado por requestReset es una copia: completarlo o cancelarlo desde el consumidor no modifica la barrera privada.

La espera temporal usa Condition.awaitNanos con el restante original. No se reinicia un sleep ni se cambia instantáneamente de piso. Una cabina detenida después de reset espera en condición; una nueva época o cierre la despiertan. Otra cabina puede seguir prestando servicio mientras una retorna.

No esperar futuros en EDT ni dentro de callbacks ejecutados por el actor. Para actualizar Swing usar invokeLater. Un timeout del consumidor no equivale a una finalización fallida del retorno ni autoriza publicar RESET. Las duraciones configuradas pueden superar los ocho segundos usados como plazo de las pruebas.

## 6. Pruebas añadidas y adaptadas

CabinResetScenarios y CabinResetTest contienen los mismos 32 casos: restante físico exacto; descarte de parada activa y congelada; rechazo atómico de época inválida; imposibilidad de confirmar fuera de piso 1; reset inicial; retorno por todos los pisos; movimiento ascendente y descendente; retorno desde Stop; prioridad sobre cien órdenes encoladas; cancelación de canónica y alias; sustitución de Stop pendiente; replay durante y después de retornar; destino y época inválidos; operación distinta durante retorno; rechazo de mensajes viejos; reutilización de identidades en nueva época; reserva de ID de reset; cierre durante retorno; trabajador no iniciado/cerrado; ambas rutas submit; cien épocas consecutivas; independencia de dos cabinas; bloqueo de servicio y otros controles; aislamiento del futuro visible; y cancelación explícita de una recogida externa.

Los casos de reloj explícito comprueban 60 ms restantes de un tramo de 100 ms observado a los 40 ms y retorno adyacente posterior. Las pruebas reales comprueban tiempos mínimos por tramo, eventos correlacionados y final físico. No se usan sleeps. La parada de un minuto se cancela y el retorno debe terminar dentro del plazo de ocho segundos, demostrando que no espera esa duración antigua.

Los casos antiguos rejectionDoesNotKillWorker (T22) y rejectedCommandHasNoCompletion (T27) se adaptan para enviar un reset con época no creciente, en lugar de suponer que todo reset aún carece de implementación. Conservan su finalidad de comprobar rechazos recuperables y ausencia de falsa finalización. La comprobación directa de CommandLedger en T26 sigue rechazando reset porque exige el canal de control; se aclara su mensaje. Sus cantidades de pruebas no cambian.

## 7. Archivos de implementación

- Producción: ElevatorState.java, CommandLedger.java, ElevatorWorker.java y ExecutionEvent.java.
- Pruebas nuevas: CabinResetScenarios.java y CabinResetTest.java.
- Escenarios anteriores adaptados: ElevatorWorkerScenarios.java, ConcurrentAdmissionScenarios.java y ExecutionEventScenarios.java.
- Documentación nueva: esta ficha, T37-codigo-completo.md y T37-validacion-local.md.
- Documentación actualizada: plan y cierre confirmado de T36 en sus fichas. Se normaliza el EOF del lector de código T36, quitando la línea vacía adicional detectada por Git.

El ZIP contiene el proyecto completo con las rutas desde la raíz Maven. Se excluyen .git, target y logs temporales. Los nueve archivos Java nuevos o modificados se incluyen íntegros y también pueden leerse en [T37-codigo-completo.md](T37-codigo-completo.md). No se cambian POM, dependencias, controlador ni manejador global para T37.

## 8. Incorporación y validación PowerShell

Desde el árbol limpio de T36, ajustar la ruta de descarga si corresponde:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T37-reset-prioritario-cabina.zip" -DestinationPath . -Force
mvn -Dtest=CabinResetTest test
mvn "-Dtest=CabinResetTest,ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,SimulationControllerTest,StopContinuationTest" test
mvn clean verify
```

Se esperan 32 pruebas específicas, 160 en el grupo indicado y 329 acumuladas sobre la base de 297 de T36. Confirmar los números reales y BUILD SUCCESS. Para los mismos escenarios independientes después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.simulation.CabinResetScenarios
```

Salida esperada: `T37: 32 escenarios satisfactorios.` No hay un botón de reset global nuevo ni una demostración de T38 en esta entrega.

## 9. Commit sugerido

Después de validar Maven, marcar T37 completada en el plan y actualizar el estado de esta ficha. Incluir los archivos explícitos:

```powershell
$archivosT37 = @(
    'src/main/java/com/elevatormanager/model/ElevatorState.java'
    'src/main/java/com/elevatormanager/simulation/CommandLedger.java'
    'src/main/java/com/elevatormanager/simulation/ElevatorWorker.java'
    'src/main/java/com/elevatormanager/simulation/ExecutionEvent.java'
    'src/test/java/com/elevatormanager/simulation/CabinResetScenarios.java'
    'src/test/java/com/elevatormanager/simulation/CabinResetTest.java'
    'src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java'
    'src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionScenarios.java'
    'src/test/java/com/elevatormanager/simulation/ExecutionEventScenarios.java'
    'docs/01-analisis-y-plan.md'
    'docs/implementacion/T37-reset-prioritario-cabina.md'
    'docs/implementacion/T37-codigo-completo.md'
    'docs/comprobaciones/T37-validacion-local.md'
    'docs/implementacion/T36-stop-y-continuacion.md'
    'docs/comprobaciones/T36-validacion-local.md'
    'docs/implementacion/T36-codigo-completo.md'
)
git diff --check
git add -- $archivosT37
git diff --cached --check
git diff --cached --stat
git commit -m "T37: implementar reset prioritario por cabina"
git status --short
```

**Siguiente tarea:** T38 — Coordinar reset global e invalidación de mensajes. La fase 6 permanece abierta.

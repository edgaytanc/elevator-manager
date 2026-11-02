# T65 — Integrar respuestas, estado y reset

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Fase:** 11 — Protocolo textual, extra 3.
- **Base aceptada:** T64 en `36af413`: 48 específicas, 1,050 totales, BUILD SUCCESS, demo correcta y árbol limpio.
- **Estado:** implementada y verificada con Java 17 y escenarios independientes; Maven Windows y aceptación de David pendientes. T66 no iniciada.

## Resultado

El manejador ya consume las cadenas V1 para seguir admisión y servicio. HallRequestLedger.onProtocol correlaciona el intento vigente por ID, cabina y época. ACK solo cambia a ASSIGNED; ARRIVED no sirve una llamada. COMPLETED necesita una parada STOPPED_AT del piso y sentido correctos. Respuestas de intentos anteriores, generaciones ajenas o llamadas ya terminadas no reabren ni completan trabajo nuevo. El flujo ExecutionEvent se conserva para logging/GUI y compatibilidad de escenarios anteriores.

ProtocolReplyTracker registra cada orden antes de enviarla. Exige ACK más terminal y, para STATUS y RESET, STATE correlacionado. Para reset verifica 1/UP, IDLE, modo NORMAL y listas vacías. La caché acepta solo la época vigente y no retrocede de revisión; permite actualizar el restante con igual revisión. Mantiene correlaciones históricas para cerrar sus propios futuros sin instalar estado obsoleto.

SimulationController integra START, STOP, PAUSE, RESUME, RESET y SHUTDOWN mediante respuestas textuales de las cabinas. La limpieza del manejador conserva su barrera nativa; el retorno de cada cabina se confirma por su ticket textual y además se contrasta con la snapshot física antes de publicar RESET. CLOSED sigue requiriendo terminar realmente los hilos y liberar recursos, no solo recibir un ACK.

queryStatus envía STATUS y retorna STATE. Funciona con actores iniciados, incluido PAUSED, STOPPED y RESET después del retorno. Una señal de control despierta el checkpoint del actor sin liberar el reloj: la consulta no reanuda la simulación ni consume tiempo de movimiento. Antes del primer Start o después del cierre se informa que el canal no está disponible; las consultas en carrera con cierre siempre se resuelven o rechazan.

## Canales, identidad y concurrencia

managerChannel/controllerChannel son capacidades por objeto interno; el origen no se obtiene de tokens de texto. El manejador puede enviar PICKUP y el controlador DESTINATION/controles. La composición comparte ProtocolIdentities entre sus cabinas para detectar un ID de comando aceptado reutilizado con otro contenido, cabina o época. El historial se conserva durante la sesión. Las firmas y sobrecargas heredadas permanecen para los escenarios tipados anteriores; la ruta de producción usa los canales con origen.

El receptor puede reproducir ACK/STATE/terminales conocidos antes de validar la época vigente, sin volver a ejecutar una orden histórica. STATUS repetido conserva su captura original; una nueva consulta usa otro ID. Un control sustituido se cancela con SUPERSEDED. Una generación posterior puede sustituir un retorno de cabina conservando el tramo físico y cancelando el reset anterior; la operación global del controlador mantiene su idempotencia mientras ya hay un reset en curso.

ProtocolResponsePump añade un solo consumidor por sesión, no un hilo por mensaje. Decodifica respuestas y completa futuros fuera del lock de la cabina. No participa en la barrera de pausa porque debe seguir recibiendo las confirmaciones. Al cerrar se unen manejador/cabinas, se drena y une este consumidor y se liberan los recursos antes de CLOSED. El cierre nativo queda como salvaguarda si no puede asignarse otro ID, sin inventar una confirmación textual.

Los datos públicos STATE no sustituyen ElevatorSnapshot, PlanningSnapshot ni el reloj preciso de animación. La herencia Request/HallRequest/CabinRequest, los trabajadores independientes, el algoritmo de asignación, los tiempos y el logging permanecen operativos.

## API nueva y actualizada

| API | Función |
| --- | --- |
| SimulationController.queryStatus(id) | Futuro de STATE correlacionado; sin bloquear el EDT. |
| SimulationController.protocolState(id) | Último estado público recibido de la generación vigente. |
| ProtocolReplyTracker.register / receive / adoptEpoch | Tickets, decodificación de respuestas y protección de caché por generación. |
| ProtocolIdentities | Registro de IDs de comando compartido por sesión, sin callbacks ni acceso a actores bajo su monitor. |
| ElevatorWorker.managerChannel / controllerChannel | Capacidades de productor para servicio/control con origen interno. |
| PauseCoordinator.wakeControls / checkpoint | Atención de controles en pausa sin liberar el reloj ni llamar al actor bajo el lock de barrera. |
| HallRequestLedger.onProtocol | Seguimiento de producción basado en mensajes V1; onEvent permanece como compatibilidad. |

Los constructores de datos del protocolo no hacen I/O ni mueven elevadores. Las finalizaciones de futuros no se ejecutan bajo los monitores de correlación ni los locks de cabina. Como con el coordinador existente, no debe bloquearse un hilo interno esperando su propia terminación; awaitTermination rechaza hacerlo también desde el consumidor de respuestas.

## Ajustes necesarios en los recorridos anteriores

START ahora espera respuestas y reserva IDs de control. Se conserva una sola aplicación entre arranques concurrentes. Mientras espera, una compuerta impide importar/confirmar un lote aunque el estado publicado todavía sea RESET. Stop/Pausa encolados detrás de Start esperan ese arranque sin bloquear el coordinador; Stop conserva los despachos ya en vuelo.

Las pruebas de importación y telemetría se adaptan al nuevo significado: preparar un lote no consume IDs; START usa uno por cabina antes del primer servicio; los contadores incluyen control y servicio. Dos pruebas GUI esperan a que el botón esté habilitado antes de pulsarlo, además de observar el estado, para sincronizarse con la entrega del recibo en EDT. No se aumentan los plazos ni se omiten casos.

## Código y comprobaciones

Los 18 archivos Java nuevos/modificados aparecen íntegros en [T65 — Código completo](T65-codigo-completo.md), incluidos los componentes centrales y escenarios previos ajustados. El ZIP contiene los 186 .java del proyecto, recursos, pom.xml y toda la documentación Markdown; excluye .git, target y logs generados. No se añaden dependencias ni se cambia release 17.

Se compilaron 133 fuentes independientes con OpenJDK 17 real. Pasaron 64 casos específicos y 1,005 anteriores en 47 grupos (1,069 independientes). Las comprobaciones cubren estado viejo, correlación incorrecta, repeticiones, pausa, consultas contra cierre, sustitución de reset y cierre/joins. La demo ejecuta STATUS durante pausa, retorno físico de dos cabinas y cierre completo desde clases y JAR auxiliar. [Resultados completos](../comprobaciones/T65-resultados-locales.md).

No se ejecutó Maven en este entorno: están pendientes las 64 específicas y el total esperado de 1,114 (1,050 base + 64). T66 todavía debe preparar/probar la demostración final del protocolo completo; no se declara cerrada toda la fase 11.

## Incorporación y validación en Windows

Comprobar que no hay cambios personales que la extracción sobrescribiría. El ZIP tiene pom.xml en su raíz y se extrae sobre el proyecto, conservando .git local.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T65-respuestas-estado-y-reset.zip" -DestinationPath . -Force
git diff --check
git diff --stat
git status --short
mvn "-Dtest=ProtocolIntegrationTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolIntegrationDemo
```

Esperado: 64 específicas y 1,114 totales, sin fallos/errores/omitidas, BUILD SUCCESS y última línea T65_INTEGRATION_OK. Las líneas T65_WIRE provienen de la suscripción real; STATUS debe mostrar HELD y clockFrozen=true durante la pausa. No hay una nueva pantalla ni un archivo de configuración adicional.

## Commit sugerido después de validar

Registrar resultados locales y marcar T65 completada en el plan después de confirmar la aceptación.

```powershell
git diff --check
git add -- src docs
git diff --cached --check
git diff --cached --stat
git commit -m "T65: integrar respuestas estado y reset"
git status --short
```

No se realiza el commit en nombre de David. La siguiente tarea es T66 — Probar y demostrar el protocolo completo.

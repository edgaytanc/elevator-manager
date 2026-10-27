# T35 — Implementar el controlador del ciclo de vida

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-27, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base:** T34, commit `94a81f2`, recibido en el ZIP del usuario.
- **Estado:** implementación y escenarios locales satisfactorios; pendiente de Maven e incorporación en el equipo de David.

## 1. Alcance

`SimulationController` posee la configuración, época, N cabinas, manejador y secuencia de IDs de comandos de una sesión. Construirlo no inicia trabajadores. Start pasa de RESET a RUNNING una vez; los siguientes Start devuelven UNCHANGED sin reenviar solicitudes. No se recrean trabajadores ni se reinicia una época por repetir Start.

Esta entrega implementa T35. Stop/continuación (T36), reset local/global (T37–T38), cierre integrado con GUI y handlers (T39), carreras de esos controles (T40), importaciones y extras conservan sus tareas. No hay botones nuevos en la ventana mínima de T11; la GUI operativa se integra en fase 9. No existen transiciones simuladas a STOPPED, PAUSED o RESET por controles todavía no implementados.

## 2. Contrato público

| Operación | Resultado y condición |
| --- | --- |
| constructor(config, epoch) | Configuración no nula, época no negativa; crea N trabajadores NEW y manejador NEW. |
| start() | Future de APPLIED en RESET; UNCHANGED en RUNNING; REJECTED durante cierre o tras CLOSED. |
| submit(Request) | Solo RUNNING; validación polimórfica, época y tipo; externa al manejador e interna a su cabina. |
| snapshots() | Lista inmutable de snapshots inmutables por cabina; no es una captura física simultánea global. |
| calls() | Historial inmutable publicado por el manejador. Puede llevar retraso respecto a ENQUEUED. |
| awaitEvent(timeout) | Suscripción propia del controlador; un consumidor lógico. ZERO permite consultar desde EDT. |
| elevatorLifecycles()/managerLifecycle() | Salud observable sin dar acceso mutable a trabajadores. |
| close() | Solicitud asíncrona de liberación mínima local; despierta actores sin hacer join en EDT. |
| awaitTermination(timeout) | Confirma terminación real y publica CLOSED; espera positiva prohibida en EDT. |

`ENQUEUED` confirma exclusivamente el envío al buzón. Las validaciones de admisión y deduplicación siguen siendo autoridad de T26/T32. La aceptación local se observa en COMMAND_ACCEPTED; el servicio en COMMAND_COMPLETED. Enviar otra vez una solicitud con el mismo UUID no significa volver a atenderla. Para una nueva solicitud se crea una identidad nueva.

La sesión permanece CLOSING hasta que se confirme `awaitTermination`. Un timeout no publica CLOSED ni implica cancelación forzada. `close()` no equivale a Stop: cancela la ejecución de la sesión. Esta liberación mínima permite probar y usar T35 sin dejar hilos vivos; T39 completará el cierre integrado, su supervisión y recursos de logging.

## 3. Concurrencia

Un ejecutor de un hilo llamado `simulation-controller` serializa Start, envío y cierre. Las llamadas devuelven CompletableFuture; no realizan join ni movimiento en el hilo llamador. El trabajo físico sigue en N hilos `elevator-worker-ID` y el despacho en `manager-worker-epoch`.

La secuencia AtomicLong privada se comparte con el manejador y todas las internas. Usa incremento exacto, sin volver a IDs negativos al agotarse. El controlador no expone workers, ni modifica agendas, ni consume el buzón privado del manejador. Su suscripción de eventos se registra antes del arranque y conserva el flujo de las otras suscripciones.

Los callbacks de CompletableFuture no tienen hilo Swing garantizado. Para cambiar componentes usar SwingUtilities.invokeLater; no llamar get/join desde EDT ni esperar terminación dentro de callbacks ejecutados por el coordinador. El consumidor debe drenar eventos: el canal conserva eventos en memoria y no tiene límite de capacidad, como los canales de las tareas previas.

Si falla el arranque, la futura termina excepcionalmente, `failure()` conserva la causa y se solicita cierre de todos los actores, incluso los todavía NEW. No se publica RUNNING parcial. Los fallos posteriores de actores se observan en sus ciclos de vida/eventos; T35 no agrega recuperación ni reasignación automática.

## 4. Archivos nuevos

- `src/main/java/com/elevatormanager/simulation/SimulationController.java`
- `src/test/java/com/elevatormanager/simulation/SimulationControllerScenarios.java`
- `src/test/java/com/elevatormanager/simulation/SimulationControllerTest.java`
- `docs/implementacion/T35-controlador-ciclo-de-vida.md`
- `docs/comprobaciones/T35-validacion-local.md`

Se actualiza `docs/01-analisis-y-plan.md` con el registro de implementación. Se conserva sin marcar la tarjeta hasta que David confirme la validación Maven. No cambian clases anteriores, POM ni dependencias.

## 5. Escenarios

1. RESET inicial, N cabinas en 1/UP, trabajadores NEW y vistas inmutables.
2. Cincuenta Start repetidos tras un único inicio.
3. Treinta y dos productores concurrentes; exactamente un APPLIED.
4. Internas/externas rechazadas antes de Start, sin comandos residuales.
5. Enrutamiento polimórfico de ambos subtipos y servicio real.
6. ID, piso, límites direccionales y época inválidos sin admisiones parciales.
7. Repetir Start con trabajo activo no reenvía ni duplica el servicio.
8. IDs únicos entre comandos de cabina y manejador.
9. Reenvío de UUID ya atendido conserva deduplicación.
10. N=1/P=1: interna válida sin movimiento, externa imposible rechazada.
11. Start desde EDT, protección de esperas y pulso Swing durante un viaje.
12. Cierre repetido con trabajo y rechazo de reinicio/entradas posteriores.
13. Cerrar antes del primer Start impide arrancar.
14. Argumentos, timeout negativo y consulta inmediata de terminación.

Los escenarios usan eventos, latches, futuros y plazos de ocho segundos; no usan sleeps. No se presenta la duración de los futuros como tiempo de atención física.

## 6. Incorporación y validación en PowerShell

El ZIP contiene una copia completa del proyecto en su raíz, con fuentes y documentación; excluye `.git`, `target` y registros temporales. Está basado exactamente en el proyecto recibido hasta T34. Extraer sobre el árbol de T34 solo después de revisar que no existan modificaciones propias que se quieran conservar.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T35-controlador-ciclo-de-vida.zip" -DestinationPath . -Force
mvn -Dtest=SimulationControllerTest test
mvn clean verify
```

Se esperan 14 pruebas específicas y 276 acumuladas si se conserva la base de 262 pruebas de T34. Las cifras deben confirmarse con el resultado real de Maven. Para ejecutar los mismos 14 escenarios sin el lanzador JUnit, después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.simulation.SimulationControllerScenarios
```

Resultado esperado: `T35: 14 escenarios satisfactorios.` Enviar el resumen de Maven antes de cerrar formalmente la tarjeta. No se necesita abrir una nueva ventana: T35 comprueba el controlador y los actores sin integración de GUI.

## 7. Commit sugerido

Después de BUILD SUCCESS, marcar T35 en el plan y actualizar el estado de esta ficha. Revisar los seis archivos y registrar:

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/simulation/SimulationController.java src/test/java/com/elevatormanager/simulation/SimulationControllerScenarios.java src/test/java/com/elevatormanager/simulation/SimulationControllerTest.java docs/implementacion/T35-controlador-ciclo-de-vida.md docs/comprobaciones/T35-validacion-local.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git commit -m "T35: implementar controlador del ciclo de vida"
git status --short
```

**Siguiente tarea:** T36 — Implementar Stop y continuación con Start. La fase 6 permanece abierta.

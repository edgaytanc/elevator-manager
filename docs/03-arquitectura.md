# 03 — Arquitectura del sistema

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-19, Guatemala.
- **Estado:** diseño propuesto; clases y contratos aún no implementados.
- **Base:** [01 — Plan](01-analisis-y-plan.md) y [02 — Decisiones](02-decisiones.md).

## 1. Objetivos y límites

Separar presentación, ciclo de vida, asignación y movimiento. Asegurar N cabinas independientes y un manejador independiente, sin ejecutar esperas en Swing. El dominio se puede probar sin abrir ventanas. La comunicación textual y la temporización pausable son contratos previstos desde el diseño, aunque sus extras se completen después de la versión base.

Una sola JVM contiene el programa. No se agregan servidor, base de datos, comunicación de red ni procesos externos. «Independiente» significa trabajador con estado y cola propios, no aislamiento de memoria entre procesos.

## 2. Estructura física prevista

```text
elevator-manager/
  pom.xml
  README.md
  .gitignore
  docs/
    01-analisis-y-plan.md
    02-decisiones.md
    03-arquitectura.md
    04-algoritmo-asignacion.md
    05-protocolo.md
    06-pruebas.md
  src/
    main/
      java/com/elevatormanager/
        app/
        model/request/
        simulation/
        dispatch/
        protocol/
        io/
        logging/
        ui/
      resources/
    test/
      java/com/elevatormanager/
        model/
        simulation/
        dispatch/
        protocol/
        io/
      resources/requests/
  examples/requests/
  evidence/
  logs/
  target/
```

Esta entrega crea únicamente `docs/01`..`06`. Las demás carpetas representan el diseño futuro. `07-manual-usuario.md` y `08-evidencias.md` se agregarán conforme exista comportamiento verificable. `target/` y los registros generados se excluyen del control de versiones.

## 3. Componentes y dependencias

| Paquete | Tipos previstos | Responsabilidad y dependencia |
| --- | --- | --- |
| `app` | `ElevatorManager` | Entrada `main`; crea configuración, composición de objetos y ventana en EDT. |
| `model` | `SimulationConfig`, `ElevatorState`, `ElevatorSnapshot`, `Direction`, `ElevatorPhase`, `RoutePlan` | Datos e invariantes; no conoce Swing ni archivos. |
| `model.request` | `Request`, `HallRequest`, `CabinRequest` | Solicitudes con herencia funcional. |
| `simulation` | `SimulationController`, `ManagerWorker`, `ElevatorWorker`, `SimulationClock` | Ciclo de vida, propiedad del estado y ejecución concurrente. Depende del dominio, estrategia y canales. |
| `dispatch` | `DispatchStrategy`, `EstimatedTimeDispatchStrategy`, `RoutePlanner`, `PickupEstimate` | Proyección pura del recorrido y selección; sin esperas ni escritura en cabinas. |
| `protocol` | `Command`, `MessageCodec`, `MessageParser`, `ElevatorMailbox`, `WorkerEvent` | Frontera textual, validación sintáctica y transporte en memoria. |
| `io` | `RequestFileReader`, `PreparedRequestBatch` | Leer/validar texto; producir lote inmutable antes de confirmar. |
| `logging` | `LoggerFactory`, `EventFormatter`, puente de logs a UI | Handlers de archivo/terminal y eventos legibles. |
| `ui` | `MainFrame`, `ConfigurationDialog`, `ControlPanel`, `ElevatorPanel`, `BuildingPanel`, `UiEventBridge` | Entradas y vistas; depende de controlador/snapshots, nunca de colecciones internas de trabajadores. |

Los nombres son compromisos de diseño orientativos, salvo `ElevatorManager`, que lo exige el enunciado. Si se renombran tipos durante el desarrollo, actualizar este documento.

## 4. Herencia y polimorfismo previstos

`Request` es una clase abstracta con identidad, época, piso y fecha. Define la operación de validación contra un contexto de configuración y la descripción de la solicitud.

| Subtipo | Datos adicionales | Especialización |
| --- | --- | --- |
| `HallRequest` | Dirección deseada. | Valida sentido posible en el piso; describe una recogida compartida, sin ID de cabina elegido por usuario. |
| `CabinRequest` | ID de cabina. | Valida que exista cabina; describe un destino interno sin sentido externo. |

El importador y el controlador manejan una colección de `Request` y ejecutan el comportamiento especializado mediante referencias de tipo base. El enrutamiento al manejador o a una cabina conserva el tipo concreto. La aceptación exige demostrar que ambos subtipos se usan; una interfaz de estrategia o heredar de `JFrame` no serán la única justificación de herencia del proyecto.

## 5. Hilos, propiedad y canales

| Ejecutor | Qué hace | Qué no puede hacer |
| --- | --- | --- |
| EDT de Swing | Lee formularios, actualiza componentes, dibuja animación con snapshots. | Esperar movimiento, leer grandes archivos, esperar `join` o confirmaciones. |
| Coordinación del controlador | Serializa acciones de ciclo de vida y confirmaciones globales. Puede usar un ejecutor único con cola. | Modificar directamente la agenda de una cabina. |
| `manager-worker` | Consume externas, estima, asigna y sigue sus eventos. | Dormir hasta que termine un viaje o editar el estado de una cabina. |
| `elevator-worker-ID` | Único escritor del estado/agenda de esa cabina; consume controles y servicio. | Actualizar Swing o esperar respuestas de otro trabajador sosteniendo su bloqueo. |
| Ejecutor de I/O | Lee archivo y produce lote validado; entrega resultado al controlador. | Confirmar importación sin revalidar RESET/época. |

Se permiten los ejecutores de coordinación/I/O auxiliares; los hilos exigidos son el manejador y los N trabajadores de cabinas. Se crean una vez por sesión; Start/Stop no los duplica.

**Canales previstos:** cola de externas al manejador; buzón por cabina con prioridad de control y entrada de servicio; canal de respuestas hacia el coordinador/manejador. Una respuesta se enruta al propietario de su seguimiento, no se consume por dos lectores compitiendo sobre una sola cola.

**Escritura única:** un snapshot contiene revisión creciente, época, piso, siguiente piso, dirección, fase, progreso/tiempo restante, frontera comprometida y copias inmutables de destinos y llamadas. Su publicación debe ser atómica. El registro global de admisiones se protege en el controlador; la agenda concreta pertenece a su cabina.

## 6. Modelo de estado por cabina

| Campo | Regla |
| --- | --- |
| `id` | Único, entre 1 y N durante la sesión. |
| `epoch` | Generación activa del sistema. |
| `revision` | Aumenta con cambios observables; permite detectar proyecciones obsoletas. |
| `currentFloor` | Último piso alcanzado/dejado; cambia solo al completar un tramo. |
| `nextFloor` | Piso adyacente durante tránsito; ausente estando alineada con un piso. |
| `direction` | UP/DOWN; UP al inicio y tras reset. |
| `phase` | IDLE, MOVING, DWELLING, HELD o TERMINATED; HELD conserva fase suspendida. |
| `remainingDuration` | Restante monotónico de tramo/parada; congelado al pausar. |
| `routePlan` | Solicitudes internas/externas, barrido comprometido y trabajos diferidos. |
| `mode` | Servicio normal o retorno por reset; estado global restringe avance. |

El trabajador gestiona comandos durante la espera temporal. No basta un `sleep` largo que impida reaccionar a controles; debe poder despertar, recalcular restante y volver a esperar.

## 7. Flujos principales

### 7.1 Llamada externa

1. GUI valida campos básicos; controlador comprueba RUNNING, límites y duplicado funcional.
2. Manejador recibe solicitud, toma snapshots y usa la estrategia de 04.
3. Emite mensaje textual de recogida con época e identidad; cabina valida y responde.
4. Manejador registra asignación al recibir aceptación. No la declara atendida aún.
5. Cabina incorpora solicitud a agenda, se desplaza y comienza una parada compatible.
6. Emite finalización correlacionada; manejador cierra la externa y la vista actualiza pendientes.

### 7.2 Selección interna

GUI/controlador validan ID/piso y envían destino al buzón de esa cabina; no necesitan seleccionar otra. La cabina enciende el destino cuando lo admite. Al iniciar su servicio lo retira de pendientes y notifica finalización. Hasta que exista ese evento, un ACK no apaga el indicador.

### 7.3 Importación

El controlador captura estado/época RESET y encarga lectura. El lector valida todo a una lista de solicitudes sin ejecutar nada. Antes de sustituir el lote, el controlador vuelve a verificar RESET y misma época. Start entrega las entradas al flujo normal en orden; los hilos se ejecutan concurrentemente y el orden de finalización no está garantizado por el orden del archivo.

### 7.4 Reset global

1. Controlador bloquea nuevas admisiones y pasa a RESETTING; incrementa época.
2. Invalida lote preparado, externas viejas y envíos pendientes; emite reset prioritario a cada cabina.
3. Cada cabina adopta época nueva, descarta agenda antigua y despierta si estaba suspendida.
4. Completa tramo físico parcial si existe; retorna a 1 sin paradas de servicio.
5. Publica piso 1/UP y confirmación terminal de reset.
6. Solo cuando todos confirman, el controlador publica RESET. Respuestas de épocas previas no cambian ese estado.

### 7.5 Cierre y fallos

Cerrar cancela avance, despierta trabajadores, espera su terminación fuera del EDT, cierra handlers y finalmente publica CLOSED. Un timeout de supervisión se informa como incidencia, no como éxito. Una cabina fallida se marca no disponible; no se reasigna automáticamente una solicitud que pudiera haber sido atendida sin confirmación.

## 8. Logs y presentación

Cada transición física relevante genera un evento estructurado. Sus consumidores construyen archivo por cabina, terminal y vista gráfica acotada. Registrar origen/destino en MOVE_STARTED, piso en ARRIVED, solicitudes atendidas en STOP_STARTED, dirección anterior/nueva en DIRECTION_CHANGED y comienzo/final del reset. Al atender destinos, log y snapshot deben corresponder al mismo cambio de dominio.

La animación interpola progreso publicado y tiempo monotónico únicamente mientras RUNNING o RESETTING lo permitan; no decide cuándo se alcanza un piso. El modelo sigue siendo autoridad. PAUSED congela el progreso; Stop puede continuar el tramo ya iniciado hasta su frontera.

## 9. Contratos de integración y construcción

- `DispatchStrategy`: recibe solicitud y snapshots, devuelve candidato y estimación o causa de ausencia; sin modificar entradas.
- `RoutePlanner`: admite solicitudes válidas, proyecta eventos y genera siguiente acción con las mismas reglas usadas por ETA.
- `SimulationController`: acciones asíncronas de inicio/detención/pausa/reset/cierre; publica estado tras confirmaciones.
- Parser de archivo: devuelve lote completo o errores con línea; no ejecuta solicitudes.
- Parser de protocolo: mensaje válido o error controlado; validación de estado/época se hace en el receptor.
- `SimulationClock`: tiempo monotónico real y alternativa controlada para pruebas; evita acoplar las reglas a esperas reales.
- Construcción futura: `mvn clean verify`; empaquetado con nombre previsto `elevator-manager-1.0.0.jar`. Es un contrato pendiente de configurar, no un artefacto disponible.

## 10. Verificación y evolución

T10–T15 cubren estructura y diseño; T18 herencia; T22/T29 trabajadores; T35–T40 ciclo de vida; T52 EDT; T54–T66 extras. Las pruebas concretas están en [06](06-pruebas.md).

Durante desarrollo registrar clases reales, decisiones que cambiaron y evidencia de concurrencia. Al cierre, contrastar diseño con fuentes y retirar afirmaciones que el código no cumpla.

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Arquitectura y contratos previos a implementación. |

**Siguiente lectura:** [04 — Algoritmo de asignación](04-algoritmo-asignacion.md).

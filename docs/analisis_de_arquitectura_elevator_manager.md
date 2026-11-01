# Análisis de Arquitectura y Concurrencia: Elevator Manager

Este documento presenta el análisis técnico y estructural del proyecto Maven `elevator-manager`, detallando su organización en paquetes, el uso de herencia y polimorfismo, y el modelo de concurrencia basado en el patrón Actor/Worker y desacoplamiento con Swing.

---

## 1. Estructura de Paquetes y Responsabilidades

El proyecto se organiza bajo el espacio de nombres raíz `com.elevatormanager`, distribuyendo sus responsabilidades en capas modulares:

```
com.elevatormanager
├── app                 # Punto de entrada y ciclo de vida de la aplicación
├── model               # Entidades de dominio, estados inmutables y configuración
│   └── request         # Jerarquía de solicitudes (cabina y pasillo)
├── protocol            # Comandos de comunicación inter-hilos
├── dispatch            # Orquestación central, deduplicación y selección de cabinas
├── simulation          # Motor de simulación física, agendas de movimiento
│   └── time            # Coordinación de base de tiempo y pausas globales
├── ui                  # Vistas, paneles Swing y desacoplamiento gráfico (EDT)
├── io                  # Parseo y validación por lotes de archivos de solicitudes
├── logging             # Trazabilidad asíncrona en disco y buffers en memoria
└── diagnostics         # Herramientas de diagnóstico y pruebas internas
```

### Detalle de Paquetes

### `com.elevatormanager.app`
* **Propósito:** Inicialización y arranque de la aplicación.
* **Componentes principales:** 
  * `ElevatorManager`: Clase ejecutable (`main`). Configura el entorno visual, inicializa el ciclo de vida del sistema y asegura que el arranque de la interfaz se ejecute de forma segura dentro del *Event Dispatch Thread* (EDT) de Swing.

### `com.elevatormanager.model` (y subpaquete `request`)
* **Propósito:** Representación del dominio y estados de la simulación.
* **Componentes principales:**
  * `ElevatorState`: Modela las propiedades dinámicas de una cabina (piso actual, dirección de marcha, estado de movimiento o apertura de puertas, y tiempos restantes).
  * `ElevatorSnapshot`: Representación inmutable del estado de la cabina. Permite que la UI y otros hilos consulten datos sin generar condiciones de carrera ni retener bloqueos prolongados.
  * `SimulationConfig`: Parámetros estáticos de la simulación (número de elevadores $N$, número de pisos $P$, tiempo entre pisos, tiempo de espera en piso o *dwell*).
  * `SimulationState`: Máquina de estados finitos que gobierna el ciclo de vida global (`RESET`, `RUNNING`, `PAUSED`, `STOPPED`, `CLOSED`).
  * `request.Request`, `request.CabinRequest`, `request.HallRequest`: Clases del dominio que encapsulan las solicitudes originadas por usuarios.

### `com.elevatormanager.protocol`
* **Propósito:** Mensajería y desacoplamiento entre el despachador y los trabajadores de cabina.
* **Componentes principales:**
  * `Command`: Estructura base para el envío de instrucciones a los elevadores.
  * `Command.Destination`, `Command.Pickup`, `Command.Reset`: Variantes concretas de comandos procesadas en los buzones de las cabinas.

### `com.elevatormanager.dispatch`
* **Propósito:** Coordinación centralizada y toma de decisiones para llamadas externas.
* **Componentes principales:**
  * `ManagerWorker`: Actor/hilo de orquestación central. Procesa la cola global de llamadas de pasillo.
  * `ElevatorSelector`: Lógica algorítmica para determinar qué elevador debe atender una llamada en función de proximidad, sentido de marcha y carga de trabajo actual.
  * `PickupTimeEstimator`: Cálculo proyectado del tiempo estimado de llegada (*Estimated Time of Arrival* / ETA) de cada cabina.
  * `HallRequestLedger`: Registro de deduplicación de llamadas de pasillo para impedir asignaciones redundantes en el mismo piso y dirección.

### `com.elevatormanager.simulation` (y subpaquete `time`)
* **Propósito:** Ejecución de la física del edificio y coordinación temporal.
* **Componentes principales:**
  * `SimulationController`: Fachada de control para arrancar, detener, pausar, reanudar, resetear y cerrar la simulación de forma limpia.
  * `ElevatorWorker`: Hilo de trabajo independiente asignado a cada cabina física.
  * `DirectionalAgenda`: Planificador de ruta espacial basado en el algoritmo SCAN / LOOK. Organiza las paradas por sentido de avance (primero en dirección actual, luego en dirección contraria) evitando cambios bruscos de sentido.
  * `CommandLedger`: Bitácora de seguimiento de comandos encolados, ejecutados y cancelados por época.
  * `time.PauseCoordinator` y `time.PausableClock`: Infraestructura de tiempo virtual capaz de calcular deltas temporales y detener el avance físico durante pausas globales sin desfasar los cálculos.

### `com.elevatormanager.ui`
* **Propósito:** Presentación y control visual mediante Java Swing.
* **Componentes principales:**
  * `MainWindow`: Marco principal (`JFrame`) que integra todos los submódulos visuales.
  * `ConfigurationPanel`, `SystemControlPanel`: Vistas para captura de parámetros iniciales y botones de control del ciclo de vida (`Start`, `Stop`, `Reset`, `Pause`, `Resume`).
  * `CabinDashboardPanel` y `CabinPanel`: Visualizadores del estado de cada cabina (posición, estado de puertas, agenda) y botones de llamadas de cabina.
  * `HallCallPanel`: Panel para ingresar llamadas externas de piso (Subir / Bajar).
  * `UiSnapshotPump` y `RefreshGate`: Mecanismo desacoplador que consulta periódicamente instantáneas del modelo y programa actualizaciones en el EDT con una cadencia máxima controlada (aprox. 10 Hz), protegiendo la responsividad de la interfaz.

### `com.elevatormanager.io`
* **Propósito:** Procesamiento por lotes y validación de archivos de entrada.
* **Componentes principales:**
  * `RequestFileReader`: Lector de archivos de texto (`.txt`) con directivas `CABIN` y `HALL`. Maneja casos límite como firmas BOM UTF-8, finales de línea heterogéneos (CRLF / CR) y validaciones sintácticas.
  * `PreparedRequestBatch` y `BatchDiagnostic`: Encapsulan el lote analizado y los reportes de error o éxito previo a la inyección al despachador.

### `com.elevatormanager.logging`
* **Propósito:** Auditoría y registro de eventos en tiempo real.
* **Componentes principales:**
  * `SessionLog` y `LoggerFactory`: Gestión de carpetas y archivos segregados por sesión (`logs/sesion-<UUID>/elevadorN.log` y `manejador.log`).
  * `TraceMailbox` y `RecentEventBuffer`: Estructura en memoria tipo buffer circular que alimenta la consola de visualización gráfica (`EventViewerPanel`) sin provocar sobrecarga de memoria.

---

## 2. Aplicación de Herencia y Polimorfismo

La herencia en el proyecto se diseñó para modelar jerarquías cerradas y seguras donde se requiere procesamiento polimórfico:

```
                    Request (Clase Base)
                   ┌──────────┴──────────┐
                   ▼                     ▼
             CabinRequest           HallRequest
         (elevatorId, floor)     (floor, direction)


                    Command (Clase Base)
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
Command.Destination  Command.Pickup     Command.Reset
```

### 1. Jerarquía de Peticiones (`com.elevatormanager.model.request`)
* **Clase base:** `Request`
  * Contiene los atributos transversales a cualquier intención de viaje: identificador único `UUID`, época/sesión activa `epoch`, marca de tiempo de creación `createdAt` y piso objetivo `floor`.
* **Subclases:**
  * `CabinRequest extends Request`: Modela una llamada interna; añade el identificador del elevador origen (`elevatorId`).
  * `HallRequest extends Request`: Modela una llamada externa de pasillo; añade la dirección deseada (`Direction.UP` o `Direction.DOWN`).
* **Uso Polimórfico:** La agenda de paradas (`DirectionalAgenda`) y los filtros del despachador procesan listas genéricas de `Request`, delegando la resolución de prioridades según el tipo de instancia concreta.

### 2. Jerarquía de Comandos del Protocolo (`com.elevatormanager.protocol`)
* **Clase base:** `Command`
  * Establece la estructura de mensaje inter-hilo: identificador de comando, cabina destino y época de emisión.
* **Subclases concretas:**
  * `Command.Destination extends Command`: Transporta una `CabinRequest` hacia una cabina específica.
  * `Command.Pickup extends Command`: Asigna una `HallRequest` previamente resuelta por el despachador.
  * `Command.Reset extends Command`: Orden prioritaria que instruye a la cabina a vaciar su agenda y retornar de inmediato al piso 1.

### 3. Jerarquía de Componentes Visuales (`com.elevatormanager.ui`)
* Las clases visuales extienden los componentes estándar de Swing (`javax.swing.JPanel`, `javax.swing.JFrame`), sobrescribiendo comportamientos de pintado y gestión de eventos de interfaz de usuario.

---

## 3. Concurrencia, Sincronización y Modelo de Hilos

El sistema sigue el **patrón Actor / Worker activo**, asegurando que el cálculo de la simulación y la entrada/salida no degraden el hilo visual principal.

### Arquitectura de Hilos

```
┌────────────────────────────────────────────────────────┐
│                   Swing EDT (UI)                       │
│ (MainWindow, Panels, Captura de eventos de usuario)    │
└────────────┬─────────────────────────────▲─────────────┘
             │ Comandos / Solicitudes      │ Muestreo (10 Hz)
             ▼                             │ (UiSnapshotPump)
┌───────────────────────────┐  ┌───────────┴─────────────┐
│  ManagerWorker (Thread)   │  │    ElevatorSnapshots    │
│  (Orquestador Central)    │  │   (Lectura inmutable)   │
└────────────┬──────────────┘  └───────────▲─────────────┘
             │ Command.Pickup              │
             ▼                             │
┌──────────────────────────────────────────┴─────────────┐
│   ElevatorWorker 1 .. N (Hilos independientes)         │
│   (Consumen BlockingQueue, ejecutan DirectionalAgenda) │
└────────────────────────────────────────────────────────┘
```

### 1. Hilos de Cabina (`ElevatorWorker`)
* Cada cabina opera sobre **su propio hilo de ejecución dedicado**.
* **Consumo seguro:** Cada elevador contiene un buzón concurrente bloqueante (`BlockingQueue<Command>`), evitando condiciones de carrera al recibir comandos desde el despachador o la UI.
* **Base temporal precisa:** El movimiento tramo a tramo (`MOVING`) y los tiempos de apertura de puertas (`DWELLING`) se calculan calculando deltas de tiempo real mediante `PausableClock`, lo que permite pausar la simulación sin congelar el hilo ni usar `Thread.sleep` arbitrarios que rompan la sincronización.

### 2. Hilo Despachador Central (`ManagerWorker`)
* Corre en un hilo separado de las cabinas y de la interfaz gráfica.
* Es responsable de recibir llamadas de pasillo, deducirlas a través de `HallRequestLedger`, evaluar métricas de proximidad con `ElevatorSelector` y despachar la tarea al elevador con menor costo estimado.

### 3. Concurrencia en I/O y Procesamiento por Lotes
* La lectura y análisis léxico de ficheros (`RequestFileReader`) no se ejecuta en el hilo de la UI. Se procesa a través de tareas asíncronas (`CompletableFuture`), manteniendo la interfaz responsiva ante archivos con miles de peticiones.

### 4. Aislamiento del EDT (Event Dispatch Thread)
* **Bombeo de instantáneas (`UiSnapshotPump` y `RefreshGate`):** En lugar de que cada elevador intente actualizar la interfaz directamente al cambiar de estado, un hilo planificador consulta instantáneas inmutables (`ElevatorSnapshot`) y las envía al EDT a una frecuencia regulada (máximo 10 actualizaciones por segundo). Esto previene la saturación del despachador de eventos de Swing.
* **Control de Épocas e Invalidación:** Al accionar comandos como `Stop` o `Reset`, la UI bloquea interacciones y eleva el identificador de época (`epoch`). Cualquier comando encolado perteneciente a una época anterior es descartado inmediatamente por los trabajadores concurrentes.
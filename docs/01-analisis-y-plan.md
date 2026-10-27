# Elevator Manager — Análisis del enunciado y plan Kanban

- **Versión:** 1.0.1
- **Fecha:** 19 de octubre de 2026, Guatemala.
- **Fuente analizada:** `enunciado-java.md`, copia proporcionada `01-enunciado-java.md`.
- **Estado:** planificación; ninguna tarea de implementación se declara realizada.
- **Objetivo:** construir y entregar un simulador Java que cumpla los requisitos obligatorios y las tres oportunidades de puntos extra.

## 1. Qué hay que hacer

Hay que desarrollar una aplicación Java de escritorio que simule un edificio con una cantidad configurable de pisos y elevadores. El usuario llama un elevador desde un piso indicando si quiere subir o bajar; el manejador decide cuál debe atenderlo. Cuando se selecciona un destino dentro de una cabina, ese elevador incorpora la parada a su recorrido.

El comportamiento debe ejecutarse en paralelo: un hilo de trabajo para el manejador y uno para cada elevador, además del hilo gráfico de Swing. La palabra «proceso» del enunciado se implementa mediante hilos, porque la sección de estructura exige Threads; no hace falta abrir procesos separados del sistema operativo.

Cada elevador tiene estado propio, solicitudes propias y un archivo propio de registros. Debe desplazarse con tiempos configurables, atender paradas, conservar el sentido de su recorrido cuando corresponda y responder al reset del sistema. La interfaz permite controlar y observar la simulación sin bloquearse mientras los elevadores se mueven.

### 1.1 Entradas y salidas

| Elemento | Contenido exigido |
| --- | --- |
| Configuración inicial | Cantidad de elevadores, cantidad de pisos, tiempo entre pisos y tiempo de parada, ambos en milisegundos. |
| Llamada externa | Piso de origen y dirección deseada. La recibe y asigna el manejador. |
| Selección interna | ID del elevador y piso de destino. Puede resolverla el propio elevador. |
| Archivo de solicitudes | Lista importable únicamente cuando el sistema está en reset: al inicio o después de completar uno. |
| Controles | Start, Stop y Reset; para el extra, Pausar y Reanudar. |
| Estado visible por elevador | Piso actual, destinos internos encendidos y llamadas externas asignadas. También conviene mostrar dirección y estado operativo. |
| Registros | Consola del sistema operativo y archivo independiente por elevador; mostrar además una vista de eventos en la interfaz. |

### 1.2 Reglas que no se pueden perder

1. Todos los elevadores comienzan en piso 1 y con dirección hacia arriba.
2. Durante el traslado entre pisos, el piso actual sigue siendo el último piso dejado; cambia únicamente al llegar al siguiente piso.
3. Cada elevador administra sus comandos de forma independiente y segura frente a accesos concurrentes.
4. Una llamada externa siempre pasa por el manejador, aunque solo exista un elevador.
5. Un elevador que está subiendo no regresa inmediatamente por una nueva llamada: primero debe completar el tramo comprometido del recorrido. Se propone aplicar la misma regla al bajar.
6. Puede recoger llamadas intermedias compatibles con su dirección sin abandonar el recorrido comprometido.
7. La asignación considera ubicación, dirección y trabajo pendiente; elegir siempre el ID menor o siempre el más cercano por distancia no basta.
8. Al atender un destino interno, su botón deja de aparecer encendido. Una llamada externa de sentido contrario en el mismo piso no se considera atendida automáticamente.
9. Reset elimina el trabajo anterior y devuelve físicamente las cabinas al piso 1, respetando tiempos de movimiento. Solo entonces termina el reset.
10. Deben registrarse salida/subida/bajada, llegada, parada, reset y cambio de dirección, identificando el elevador.
11. La clase de entrada debe llamarse exactamente `ElevatorManager`.
12. Debe existir herencia con una relación de dominio válida, paquetes coherentes, hilos, logger e interfaz gráfica.

### 1.3 Alcance obligatorio y extras

| Tipo | Incluye |
| --- | --- |
| Obligatorio | Modelo, asignación, concurrencia, configuración, controles, carga desde texto, GUI de estados y registros. |
| Extra 1 | Pausar y reanudar conservando solicitudes y avance temporal. |
| Extra 2 | Representación animada de cabinas desplazándose por un edificio, además de mostrar su estado. |
| Extra 3 | Comunicación textual real mediante comandos y argumentos entre el manejador y los elevadores. |
| No exigido | Base de datos, servidor web, sockets, autenticación, capacidad/peso de cabinas, pasajeros individuales o inteligencia artificial. |

La documentación, pruebas, empaquetado y evidencias que propone este plan permiten demostrar el cumplimiento; el enunciado no fija una rúbrica de puntos, un informe con extensión específica ni nombres obligatorios para esos documentos.

## 2. Decisiones propuestas para cerrar ambigüedades

Estas decisiones son de diseño, no requisitos textuales adicionales. Registrarlas antes de implementar y adaptarlas si el docente aclara otro criterio.

| Tema | Criterio propuesto |
| --- | --- |
| Tecnología | JDK 17 como nivel de compilación, Swing, Maven y APIs estándar de concurrencia y logging. Es una base seleccionada, no una afirmación de que sea la versión más reciente. |
| Pisos válidos | Numeración 1..P; N ≥ 1 y P ≥ 1. Edificio de un piso válido: solo destinos internos al piso 1, sin llamadas externas direccionales posibles. |
| Tiempos | Enteros positivos en milisegundos; validar desbordamientos al convertir o sumar. |
| Límites | No permitir bajar desde piso 1 ni subir desde el último. No fijar un máximo arbitrario de elevadores como requisito académico. |
| Stop | Detener el avance y despacho preservando pendientes; puede completar el tránsito de un piso ya iniciado y quedar en una frontera segura. Start continúa desde ese estado. No equivale a reset. |
| Pausa extra | Congelar también el tramo/parada en curso, conservando el tiempo restante y la posición visual; Reanudar continúa exactamente desde ahí. Esta diferencia la separa de Stop. |
| Reset | Prioritario; invalida comandos previos, termina de forma coherente cualquier tramo entre pisos y retorna al piso 1 sin atender llamadas antiguas. Restablece dirección hacia arriba al finalizar. |
| Reset desde pausa/Stop | Debe avanzar el retorno a piso 1 aunque el sistema estuviera detenido; al terminar queda listo en RESET, esperando Start. |
| Solicitudes durante reset | Rechazarlas con un mensaje claro. No dejarlas entrar accidentalmente en la siguiente ejecución. |
| Solicitudes en pausa/Stop | Rechazarlas en esta propuesta para simplificar la semántica; la GUI indica por qué. Los pendientes anteriores permanecen. |
| Carga inicial | Importar valida y prepara trabajo; no mueve cabinas hasta Start. El estado RESET permite tener solicitudes preparadas sin que se estén ejecutando. |
| Varias importaciones | Cada importación válida sustituye el lote preparado anterior; cancelar o fallar deja intacto el lote existente. |
| Solicitudes duplicadas | Unificar llamadas externas aún pendientes por piso y dirección, y destinos internos por elevador y piso; permitir nuevas llamadas después de atender las anteriores. |
| Piso actual como destino | Cabina detenida: realizar una parada si corresponde. En tránsito: programar regreso, sin fingir que sigue físicamente disponible en el piso dejado. |
| Empates al asignar | Resolver de manera determinista, por ejemplo menor ID después de comparar costo y carga. |
| Personas | Se simulan solicitudes y paradas, sin exigir entidades pasajero, ocupación o conteos. |

Estados del diseño detallado: `RESET`, `RUNNING`, `STOPPING`, `STOPPED`, `PAUSING`, `PAUSED`, `RESETTING`, `CLOSING`, `CLOSED`. Mientras se completa Stop, no aceptar Start hasta alcanzar `STOPPED`. Reset cancela el trabajo preparado o activo. El extra de pausa debe permitir volver a RUNNING y ejecutar Reset o cerrar sin bloqueos.

## 3. Herramientas, bibliotecas y preparación

| Componente | Uso | Instalación/dependencia |
| --- | --- | --- |
| JDK | Compilar, ejecutar y empaquetar Java. | Instalar una distribución JDK con herramientas completas; compilar con `release 17`. |
| Swing/AWT | Ventana de control, paneles y animación. | Incluido en el JDK con módulo `java.desktop`; no requiere descargar un JAR de Swing. |
| `java.util.concurrent` | Colas, bloqueos, condiciones y coordinación. | Incluido en Java. |
| `java.util.logging` | Archivos y consola. | Incluido en el módulo `java.logging`. |
| `java.nio.file` | Lectura del archivo de solicitudes. | Incluido en Java. |
| Maven | Compilación y empaquetado reproducibles. | Instalar y fijar versiones de plugins en `pom.xml`. |
| JUnit Jupiter | Pruebas automáticas de reglas y concurrencia. | Dependencia exclusiva de pruebas, con versión compatible con JDK 17 fijada en el POM. |
| IDE y Git | Desarrollo, depuración y control de versiones. | IDE de elección y Git recomendado; no son requisitos del enunciado. |

No hacen falta JavaFX, Log4j, Lombok ni bibliotecas de animación para este alcance. La versión exacta de Maven, JUnit y plugins se selecciona y registra durante la preparación, verificando compatibilidad; no se deja como `LATEST`.

Verificar en una terminal nueva `java -version`, `javac -version`, `jar --version` y `mvn -version`. Confirmar que Maven y el IDE usan el JDK elegido. `JAVA_HOME` apunta a la raíz del JDK, no a su carpeta `bin`; `PATH` contiene su `bin`. En Windows se pueden inspeccionar rutas con `where.exe java`, `where.exe javac` y `where.exe jar`; en Linux con `command -v`.

## 4. Arquitectura y estructura de carpetas propuestas

### 4.1 Responsabilidades

| Paquete | Clases orientativas y responsabilidad |
| --- | --- |
| `app` | `ElevatorManager`: `main`, configuración y arranque/cierre. |
| `model` | Configuración, `Elevator`, dirección, estados, instantáneas y solicitudes. |
| `model.request` | `Request` abstracta; `HallRequest` y `CabinRequest` concretas. |
| `simulation` | `SimulationController`, `ManagerWorker` y `ElevatorWorker`: ciclo de vida y ejecución paralela. |
| `dispatch` | `DispatchStrategy`, `EstimatedTimeDispatchStrategy`, cálculo y selección de cabina. |
| `protocol` | Codificador, parser, comandos y respuestas del protocolo textual extra. |
| `io` | Lectura, validación y preparación transaccional de archivos de solicitudes. |
| `logging` | Configuración de loggers, handlers y formato de eventos. |
| `ui` | Ventana, formularios, paneles de cabina, panel animado y puente al EDT. |

**Herencia real:** `HallRequest` y `CabinRequest` heredan de `Request`; comparten identidad, piso y fecha, pero representan solicitudes diferentes y sobrescriben un comportamiento útil de descripción/validación contextual. El diseño debe utilizar referencias de tipo base y despacho polimórfico en un flujo real, no limitarse a declarar `extends`. Una interfaz de estrategia o `implements Runnable` por sí solas no demuestran la herencia de clases solicitada.

**Hilos:** `ManagerWorker` consume una cola de solicitudes externas; cada `ElevatorWorker` procesa sus comandos y su agenda de paradas. El hilo de Swing solo controla y presenta. Las actualizaciones de componentes se hacen en el EDT. Usar instantáneas inmutables evita que la interfaz itere colecciones que cambian en otro hilo.

**Colas y rutas:** diferenciar la cola de entrada segura entre hilos de la agenda ordenada del recorrido. Una `BlockingQueue` permite recibir comandos, pero por sí sola no resuelve el orden eficiente de las paradas.

### 4.2 Carpetas

```text
elevator-manager/
  pom.xml
  README.md
  .gitignore
  src/
    main/
      java/com/elevatormanager/
        app/ElevatorManager.java
        model/
          request/
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
  docs/
    01-analisis-y-plan.md
    02-decisiones.md
    03-arquitectura.md
    04-algoritmo-asignacion.md
    05-protocolo.md
    06-pruebas.md
    07-manual-usuario.md
    08-evidencias.md
  evidence/
  logs/
  target/
```

`target/` se genera con Maven y `logs/` durante la ejecución; excluir sus contenidos de Git. Conservar las evidencias de entrega seleccionadas, no toda salida temporal. La clase de entrada tendrá nombre completo `com.elevatormanager.app.ElevatorManager`; el manifiesto debe apuntar a ella.

## 5. Cómo convertir este plan en Kanban

- Columnas: **Pendiente → Lista → En curso → En revisión/pruebas → Terminada**. Usar etiqueta **Bloqueada** cuando corresponda.
- Una tarjeta por ID `T01`..`T80`; fase como etiqueta o épica. Todas comienzan pendientes.
- Copiar como descripción el trabajo y el criterio de aceptación de cada tarea.
- Etiquetas de alcance: `BASE`, `EXTRA-1`, `EXTRA-2`, `EXTRA-3`, `CALIDAD`.
- Prioridades: P0 para base y preparación necesaria; P1 para extras solicitados y cierre. P1 sigue siendo parte del objetivo de este plan.
- Mantener una tarjeta de desarrollo en curso por persona; no marcar terminada una tarjeta que solo compila y aún no satisface su aceptación.
- Las dependencias por fase indican el orden recomendado. Dentro de cada fase seguir el orden de IDs, salvo tareas documentales independientes.
- Una tarjeta termina cuando existe el resultado, pasa su comprobación y queda registrado el cambio y su evidencia apropiada. No es necesario capturar pantalla de cada tarea.

## 6. Tareas por fases

### Fase 0 — Entender y fijar el alcance

**Entrada:** enunciado disponible. **Salida:** requisitos y decisiones trazables. **Dependencias:** ninguna. **Prioridad:** P0.

- [x] **T01 — Convertir el enunciado en requisitos verificables.** Etiqueta: BASE. Enumerar configuración, elevadores, manejador, GUI, carga, logs, estructura y extras. **Aceptación:** cada obligación tiene una comprobación prevista y se distinguen propuestas de requisitos literales.
- [x] **T02 — Definir semántica de los controles.** Etiqueta: BASE. Documentar Start, Stop, Reset, importación, cierre y tratamiento de solicitudes por estado; reservar pausa/reanudación. **Aceptación:** tabla de transiciones y acciones válidas sin contradicciones.
- [x] **T03 — Definir casos de uso y ejemplos manuales.** Etiqueta: BASE. Preparar secuencias de llamada externa, destino interno, parada intermedia y retorno por reset. **Aceptación:** cada secuencia tiene configuración, acciones y resultados observables esperados.
- [x] **T04 — Crear el tablero y cargar las tarjetas.** Etiqueta: CALIDAD. Crear columnas, etiquetas, IDs y dependencias de este documento. **Aceptación:** T01..T80 están identificadas una sola vez y los tres extras son visibles.

### Fase 1 — Preparar el entorno y dependencias

**Entrada:** alcance definido. **Salida:** herramientas y proyecto mínimo comprobados. **Dependencias:** fase 0. **Prioridad:** P0.

- [x] **T05 — Instalar y seleccionar el JDK.** Etiqueta: BASE. Instalar JDK completo y alinear `JAVA_HOME`, `PATH` e IDE. **Aceptación:** `java`, `javac` y `jar` funcionan y corresponden al JDK elegido.
- [x] **T06 — Instalar y verificar Maven.** Etiqueta: BASE. Configurar acceso a repositorios de dependencias. **Aceptación:** `mvn -version` usa el JDK correcto y una construcción mínima resuelve sus plugins.
- [x] **T07 — Configurar IDE y control de versiones.** Etiqueta: CALIDAD. Abrir proyecto Maven, UTF-8, depurador y repositorio Git. **Aceptación:** se puede compilar desde IDE y terminal, y existe un commit inicial sin archivos temporales.
- [x] **T08 — Configurar `pom.xml`.** Etiqueta: BASE. Fijar nivel Java 17, codificación, plugins de compilación, pruebas y JAR con `Main-Class`; agregar JUnit compatible solo en pruebas. **Aceptación:** `mvn clean verify` pasa en el esqueleto y las versiones están fijadas.
- [x] **T09 — Verificar herramientas de escritorio, hilos y logging.** Etiqueta: BASE. Ejecutar una comprobación temporal de ventana Swing, hilo independiente y registro a consola/archivo. **Aceptación:** funcionan en el equipo de desarrollo; documentar si se requiere sesión gráfica y acceso de escritura.

### Fase 2 — Crear carpetas y diseñar la arquitectura

**Entrada:** proyecto mínimo construible. **Salida:** estructura y contratos claros. **Dependencias:** fase 1. **Prioridad:** P0.

- [x] **T10 — Crear la estructura del proyecto.** Etiqueta: BASE. Crear paquetes y carpetas de fuentes, pruebas, recursos, ejemplos, documentación y evidencias de la sección 4. **Aceptación:** rutas y declaraciones `package` coinciden; los generados quedan excluidos de Git.
- [x] **T11 — Crear la entrada `ElevatorManager`.** Etiqueta: BASE. Preparar `main`, arranque gráfico en EDT y composición de dependencias. **Aceptación:** se abre una ventana mínima tanto desde IDE como desde el JAR construido.
- [x] **T12 — Diseñar componentes y contratos.** Etiqueta: BASE. Documentar flujo GUI → controlador → manejador → elevador → eventos, con separación del algoritmo y del protocolo. **Aceptación:** cada responsabilidad tiene propietario y las clases del dominio no dependen de Swing.
- [x] **T13 — Diseñar la herencia de solicitudes.** Etiqueta: BASE. Definir `Request`, `HallRequest`, `CabinRequest` y el comportamiento polimórfico que utilizará la aplicación. **Aceptación:** la relación «es una solicitud» es válida y hay un flujo previsto que usa el tipo base.
- [x] **T14 — Diseñar sincronización y estado publicado.** Etiqueta: BASE. Definir colas, bloqueo del estado, condiciones para espera y snapshots; fijar orden de bloqueos o evitar anidarlos. **Aceptación:** ninguna colección mutable se comparte sin protección y no se espera una respuesta de otro hilo sosteniendo un bloqueo global.
- [x] **T15 — Definir invariantes y transiciones.** Etiqueta: BASE. Describir piso en tránsito, destino comprometido, atención por sentido, finalización de reset y cierre. **Aceptación:** se pueden derivar pruebas concretas y se resuelven casos en límites del edificio.

### Fase 3 — Implementar el modelo y validar entradas

**Entrada:** contratos diseñados. **Salida:** dominio comprobable sin GUI. **Dependencias:** fase 2. **Prioridad:** P0.

- [x] **T16 — Implementar configuración inmutable.** Etiqueta: BASE. Modelar N, P y tiempos con validación centralizada. **Aceptación:** acepta N/P iguales a 1; rechaza cero, negativos, texto inválido y valores fuera del rango numérico soportado.
- [x] **T17 — Implementar dirección y estados.** Etiqueta: BASE. Separar dirección de movimiento del estado operativo: detenido no significa automáticamente dirección hacia arriba. **Aceptación:** no se mezclan estados de sistema con dirección ni estado de cabina.
- [x] **T18 — Implementar solicitudes con herencia.** Etiqueta: BASE. Crear los tipos base y derivados, identidad, piso y comportamiento polimórfico. **Aceptación:** se usa herencia de clases en ejecución y se validan dirección, piso e ID con el contexto correspondiente.
- [x] **T19 — Implementar el estado independiente de cada elevador.** Etiqueta: BASE. Agregar ID, piso, dirección, fase del movimiento y agenda interna/externa diferenciada. **Aceptación:** todos comienzan en piso 1/arriba y modificar una cabina no altera otra.
- [x] **T20 — Implementar comandos y snapshots.** Etiqueta: BASE. Establecer contratos tipados para recoger, destino interno, reset y publicación inmutable del estado. **Aceptación:** las vistas pueden leer un snapshot coherente sin modificar el modelo.
- [x] **T21 — Probar las reglas del modelo.** Etiqueta: CALIDAD. Cubrir límites, herencia funcional, identidad, duplicados y encapsulación. **Aceptación:** las pruebas verifican resultados observables y rechazos; no dependen de Swing ni de esperas reales largas.

### Fase 4 — Construir el motor concurrente de elevadores

**Entrada:** modelo validado. **Salida:** cabinas independientes que se mueven correctamente. **Dependencias:** fase 3. **Prioridad:** P0.

- [x] **T22 — Implementar un trabajador por elevador.** Etiqueta: BASE. Crear hilos identificables y cola de comandos por cabina; esperar bloqueado cuando no haya trabajo. **Aceptación:** una cabina ocupada no bloquea a otra y no existe sondeo activo continuo.
- [x] **T23 — Implementar desplazamiento entre pisos.** Etiqueta: BASE. Avanzar un piso a la vez con el tiempo configurado y dirección coherente. **Aceptación:** no se sale de 1..P; el piso lógico no cambia a mitad del tramo y el destino lejano no produce teletransporte.
- [x] **T24 — Implementar parada y atención.** Etiqueta: BASE. Esperar el tiempo de parada configurado, satisfacer los pedidos compatibles y actualizar sus indicadores. **Aceptación:** se apaga el destino atendido y no se borra una llamada externa contraria todavía pendiente.
- [x] **T25 — Implementar agenda ordenada por sentido.** Etiqueta: BASE. Incorporar destinos y llamadas intermedias compatibles manteniendo el compromiso de recorrido. **Aceptación:** una solicitud detrás de la cabina no fuerza una inversión inmediata; una parada intermedia compatible sí se atiende.
- [x] **T26 — Implementar inserción concurrente y deduplicación.** Etiqueta: BASE. Recibir comandos durante movimiento/parada y distinguir trabajo pendiente del ya atendido. **Aceptación:** no se pierden destinos ni ocurren errores de modificación concurrente; puede solicitarse otra vez un piso ya atendido.
- [x] **T27 — Incorporar eventos de ejecución.** Etiqueta: BASE. Emitir salida, llegada, parada, cambio de dirección, aceptación y finalización de comandos. **Aceptación:** cada evento lleva elevador, solicitud cuando corresponda y orden interpretable por cabina.
- [x] **T28 — Comprobar paralelismo y tiempos.** Etiqueta: CALIDAD. Ejecutar al menos dos cabinas con recorridos simultáneos y comprobar trazas. **Aceptación:** los recorridos se solapan; los tiempos se verifican con tolerancia, no con igualdad exacta del planificador del sistema operativo.

### Fase 5 — Implementar el Elevator Manager y la asignación

**Entrada:** elevadores funcionales. **Salida:** distribución correcta y justificable. **Dependencias:** fase 4. **Prioridad:** P0.

- [x] **T29 — Implementar el hilo del manejador.** Etiqueta: BASE. Consumir llamadas externas desde su propia cola y procesarlas sin usar el hilo gráfico. **Aceptación:** el manejador tiene ejecución independiente y asigna incluso cuando N = 1.
- [x] **T30 — Implementar estimación de tiempo de recogida.** Etiqueta: BASE. Considerar posición, dirección, tramo restante, paradas y retorno necesario para recoger con el sentido solicitado. **Aceptación:** produce resultados justificables en escenarios pequeños y contempla una cabina cercana pero ocupada.
- [x] **T31 — Implementar selección y desempate.** Etiqueta: BASE. Elegir el menor costo estimado, comprobar estado vigente y entregar el comando a la cabina. **Aceptación:** la misma entrada estable produce una decisión determinista y las cabinas no disponibles quedan excluidas.
- [x] **T32 — Coordinar pedidos pendientes y confirmaciones.** Etiqueta: BASE. Rastrear recibido, asignado y atendido; controlar duplicados y rechazos sin duplicar responsabilidades. **Aceptación:** ninguna llamada desaparece al asignarse y no se registra como atendida antes de su parada compatible.
- [ ] **T33 — Probar reglas de dirección y recogida en ruta.** Etiqueta: CALIDAD. Cubrir llamada compatible delante, incompatible detrás y sentido contrario en el mismo piso. **Aceptación:** se cumple el recorrido comprometido y se distinguen sentidos al recoger.
- [ ] **T34 — Evaluar eficiencia y escalabilidad.** Etiqueta: CALIDAD. Ejecutar el mismo conjunto de escenarios con 1, 2 y una N mayor configurable; medir espera y movimientos. **Aceptación:** no hay supuestos de dos cabinas y se documentan las decisiones del algoritmo, sin prometer optimalidad global.

### Fase 6 — Implementar Start, Stop, Reset y cierre seguro

**Entrada:** despacho y movimiento integrados. **Salida:** ciclo de vida consistente. **Dependencias:** fase 5. **Prioridad:** P0.

- [ ] **T35 — Implementar el controlador del ciclo de vida.** Etiqueta: BASE. Centralizar transiciones y validar acciones por estado. **Aceptación:** pulsaciones repetidas de Start no crean trabajadores duplicados ni ejecutan dos veces un comando.
- [ ] **T36 — Implementar Stop y continuación con Start.** Etiqueta: BASE. Detener despacho, coordinar fronteras seguras de movimiento y preservar pendientes. **Aceptación:** STOPPING termina en STOPPED; Start continúa sin perder o volver a atender solicitudes ya completadas.
- [ ] **T37 — Implementar reset prioritario por cabina.** Etiqueta: BASE. Vaciar agenda y comandos antiguos, resolver posición en tránsito y volver al piso 1 respetando movimiento. **Aceptación:** no se teletransporta ni atiende trabajo cancelado y se restablece dirección arriba.
- [ ] **T38 — Coordinar reset global e invalidación de mensajes.** Etiqueta: BASE. Vaciar pendientes del manejador y esperar confirmación de todas las cabinas; usar época/generación o mecanismo equivalente. **Aceptación:** ningún mensaje en vuelo de la ejecución anterior reaparece y RESET solo se alcanza al terminar todos los retornos.
- [ ] **T39 — Implementar cierre ordenado.** Etiqueta: BASE. Finalizar trabajadores, despertar esperas, cerrar handlers y liberar recursos sin bloquear el EDT. **Aceptación:** cerrar con trabajo pendiente no deja hilos vivos ni archivos bloqueados por la aplicación.
- [ ] **T40 — Probar carreras del ciclo de vida.** Etiqueta: CALIDAD. Cubrir reset en movimiento y parada, Stop repetido, reset desde detenido y cierre durante reset. **Aceptación:** termina dentro de límites razonables sin interbloqueos ni eventos antiguos aplicados al estado nuevo.

### Fase 7 — Implementar logs y carga de solicitudes

**Entrada:** motor y controles estables. **Salida:** trazabilidad y lotes reproducibles. **Dependencias:** fase 6. **Prioridad:** P0.

- [ ] **T41 — Configurar logging por elevador.** Etiqueta: BASE. Crear `elevador1.log`, `elevador2.log`, etc., salida a consola y formatter legible con ID y fecha. **Aceptación:** cada archivo contiene solo su cabina y cada evento aparece una vez por destino previsto, sin duplicación por handlers heredados.
- [ ] **T42 — Completar eventos obligatorios y errores.** Etiqueta: BASE. Registrar subida/bajada, llegada, parada, reset y cambio de dirección; agregar decisiones del manejador con identificación clara. **Aceptación:** una ejecución de muestra permite reconstruir lo sucedido y un error de escritura no pasa inadvertido.
- [ ] **T43 — Definir y documentar el formato del archivo.** Etiqueta: BASE. Usar texto UTF-8 con llamadas `HALL piso direccion` y destinos `CABIN id piso`; comentarios y líneas vacías según contrato. **Aceptación:** ejemplos válidos e inválidos y política de orden/duplicados documentados; no se exige calendarización porque el enunciado no la pide.
- [ ] **T44 — Implementar parser y validación de lote.** Etiqueta: BASE. Validar archivo completo, argumentos, pisos, dirección e IDs, indicando línea del error. **Aceptación:** una línea inválida rechaza todo el lote, sin cargar parcialmente ni modificar el lote anterior.
- [ ] **T45 — Integrar importación limitada a RESET.** Etiqueta: BASE. Comprobar estado antes y al confirmar; preparar trabajo y distribuirlo al iniciar. **Aceptación:** importar no mueve cabinas; está permitido al inicio y tras reset completo, y está bloqueado en los demás estados.
- [ ] **T46 — Crear ejemplos y probar errores de lectura.** Etiqueta: CALIDAD. Incluir archivo mínimo, varios elevadores, direcciones mixtas, repetidos y errores. **Aceptación:** cancelar selector, archivo inexistente, vacío o sin permisos tienen comportamiento documentado sin romper la aplicación.

### Fase 8 — Construir la interfaz gráfica obligatoria

**Entrada:** servicios funcionales sin GUI completa. **Salida:** simulador operable desde ventanas. **Dependencias:** fase 7. **Prioridad:** P0.

- [ ] **T47 — Crear formulario de configuración.** Etiqueta: BASE. Capturar N, P y ambos tiempos, mostrando errores junto a los campos. **Aceptación:** no se inicia con valores inválidos; las unidades en milisegundos son visibles.
- [ ] **T48 — Crear panel de controles del sistema.** Etiqueta: BASE. Conectar Start, Stop, Reset, cargar archivo y cerrar con indicadores de estado. **Aceptación:** cada control se habilita por la máquina de estados y muestra cuándo se está deteniendo o reseteando.
- [ ] **T49 — Crear llamadas externas compartidas por piso.** Etiqueta: BASE. Permitir seleccionar piso y subir/bajar, sin asociar el botón de llamada a una cabina concreta. **Aceptación:** toda llamada pasa por el manejador y los botones de extremos imposibles están deshabilitados.
- [ ] **T50 — Crear paneles y botones internos de cabina.** Etiqueta: BASE. Mostrar por elevador piso, dirección, estado, destinos encendidos y llamadas externas asignadas con sentido. **Aceptación:** los paneles se generan para N y P configurables y las luces se apagan al atenderse, no al asignarse.
- [ ] **T51 — Agregar visor de eventos y adaptación de tamaño.** Etiqueta: BASE. Mostrar registros recientes, desplazamiento para muchas cabinas y límites de memoria del visor. **Aceptación:** los logs completos permanecen en archivos y la interfaz puede inspeccionar cabinas fuera del área inicial.
- [ ] **T52 — Garantizar actualización segura en EDT.** Etiqueta: BASE. Usar snapshots/eventos y limitar refrescos; sacar esperas, lectura y cierre del hilo gráfico. **Aceptación:** la ventana responde mientras varias cabinas se mueven o se carga un archivo grande y no se modifican componentes desde trabajadores.
- [ ] **T53 — Validar el recorrido completo desde GUI.** Etiqueta: CALIDAD. Configurar, iniciar, llamar, seleccionar destino, detener, continuar, resetear e importar. **Aceptación:** todo el alcance base es ejecutable por controles gráficos sin editar código ni depender de comandos de terminal durante el uso.

### Fase 9 — Implementar pausa y reanudación, extra 1

**Entrada:** versión base usable. **Salida:** suspensión precisa y reversible. **Dependencias:** fase 8. **Prioridad:** P1.

- [ ] **T54 — Implementar control temporal pausable.** Etiqueta: EXTRA-1. Medir duraciones con reloj monotónico y preservar el tiempo restante de traslados/paradas mediante condiciones. **Aceptación:** no hay avance de simulación durante pausa ni esperas activas; el tiempo de pared pausado no cuenta como movimiento.
- [ ] **T55 — Coordinar pausa global y reanudación.** Etiqueta: EXTRA-1. Suspender manejador y cabinas de forma coherente y reconocer que todas se han pausado. **Aceptación:** la GUI no anuncia PAUSED antes del punto de coordinación y Reanudar no reinicia el tramo ni pierde comandos.
- [ ] **T56 — Integrar controles y prioridades.** Etiqueta: EXTRA-1. Agregar Pausar/Reanudar, resolver Stop/Reset/cierre durante pausa y registrar transiciones. **Aceptación:** reset y cierre despiertan hilos pausados y no requieren reanudar manualmente para funcionar.
- [ ] **T57 — Probar pausa en distintas fases.** Etiqueta: CALIDAD. Pausar entre pisos, durante espera de parada y sin trabajo; repetir ciclos. **Aceptación:** piso, posición y pendientes se conservan; al reanudar se consume solo el tiempo restante con tolerancia documentada.

### Fase 10 — Implementar animación, extra 2

**Entrada:** temporización y pausa fiables. **Salida:** edificio animado ligado al modelo. **Dependencias:** fase 9. **Prioridad:** P1.

- [ ] **T58 — Diseñar el panel gráfico del edificio.** Etiqueta: EXTRA-2. Representar pisos etiquetados y un hueco/cabina por elevador, adaptando escala y desplazamiento. **Aceptación:** visualización correcta con P = 1 y con varias cabinas/pisos.
- [ ] **T59 — Implementar animación del desplazamiento.** Etiqueta: EXTRA-2. Interpolar posición con el avance temporal del modelo y refrescar con temporizador Swing. **Aceptación:** la animación coincide con salida/llegada y no modifica por sí misma el piso lógico.
- [ ] **T60 — Sincronizar animación, pausa, Stop y reset.** Etiqueta: EXTRA-2. Congelar y continuar posición según cada control y animar el retorno físico. **Aceptación:** no hay saltos por tiempo de pared acumulado durante pausa y el reset se observa de vuelta al piso 1.
- [ ] **T61 — Verificar claridad y rendimiento visual.** Etiqueta: CALIDAD. Revisar tamaño de ventana, textos, desplazamiento, refrescos y uso de recursos. **Aceptación:** las cabinas se distinguen por ID, la GUI responde y la animación no crea hilos extra por fotograma.

### Fase 11 — Implementar protocolo textual, extra 3

**Entrada:** base y extras visuales estables. **Salida:** comunicación textual efectiva y documentada. **Dependencias:** fase 10; los contratos pueden diseñarse desde fase 2. **Prioridad:** P1.

- [ ] **T62 — Especificar gramática y semántica del protocolo.** Etiqueta: EXTRA-3. Definir versión, separador, comandos, argumentos, IDs de correlación, generación, errores, aceptación y finalización. **Aceptación:** el documento permite a otra persona construir un emisor/receptor compatible sin leer la implementación.
- [ ] **T63 — Implementar codificador y parser del protocolo.** Etiqueta: EXTRA-3. Convertir comandos tipados a texto y volver a tipos validados. **Aceptación:** round-trip conserva información y mensajes malformados producen errores controlados, sin matar trabajadores.
- [ ] **T64 — Sustituir la frontera manejador–cabina por mensajes de texto.** Etiqueta: EXTRA-3. Transportar cadenas mediante colas seguras, parsearlas en el trabajador y responder por canal de eventos. **Aceptación:** las órdenes funcionales cruzan realmente la frontera en texto; imprimir una cadena en el log no cuenta.
- [ ] **T65 — Integrar respuestas, estado y reset.** Etiqueta: EXTRA-3. Relacionar ACK/ERROR/completado/estado con mensaje, cabina y generación; descartar órdenes antiguas. **Aceptación:** ACK significa aceptación, no llegada, y ninguna respuesta obsoleta altera un reset completado.
- [ ] **T66 — Probar y demostrar el protocolo completo.** Etiqueta: CALIDAD. Cubrir argumentos inválidos, comando desconocido, destinatario inexistente, duplicado y reset con mensajes en vuelo. **Aceptación:** existe una traza textual reproducible y los extras anteriores siguen funcionando después de la integración.

### Fase 12 — Integración y pruebas de aceptación

**Entrada:** funciones y extras implementados. **Salida:** evidencia verificable de cumplimiento. **Dependencias:** fase 11. **Prioridad:** P0 para base y P1 para extras.

- [ ] **T67 — Ejecutar pruebas funcionales de extremo a extremo.** Etiqueta: CALIDAD. Recorrer configuración, entradas manuales, importación, asignación, movimiento, luces y logs. **Aceptación:** los casos de la sección 8 tienen resultado registrado y no hay defectos base pendientes.
- [ ] **T68 — Ejecutar casos de dirección y eficiencia.** Etiqueta: CALIDAD. Comparar decisiones con resultados calculados a mano en escenarios controlados. **Aceptación:** se respeta el compromiso del recorrido y el costo explica por qué se seleccionó cada cabina.
- [ ] **T69 — Ejecutar estrés y carreras concurrentes.** Etiqueta: CALIDAD. Enviar ráfagas de solicitudes, deduplicados, Stop/Start, resets y pausas con varias cabinas. **Aceptación:** no hay pérdida inexplicada, interbloqueos, excepciones concurrentes ni hilos crecientes por repetición de controles.
- [ ] **T70 — Validar tolerancias temporales y modos detenidos.** Etiqueta: CALIDAD. Medir tiempos entre eventos y estado durante PAUSED/STOPPED/RESETTING. **Aceptación:** no hay tránsito más rápido que lo configurado salvo tolerancia de medición, y la pausa conserva el tiempo restante.
- [ ] **T71 — Verificar trazabilidad de requisitos y extras.** Etiqueta: CALIDAD. Relacionar cada requisito con clases, pruebas y evidencia; actualizar la matriz de esta documentación. **Aceptación:** ninguna obligación queda sin prueba y cada extra tiene demostración propia.
- [ ] **T72 — Corregir y repetir regresión afectada.** Etiqueta: CALIDAD. Resolver defectos y volver a ejecutar las pruebas de las áreas modificadas y el flujo integrado. **Aceptación:** `mvn clean verify` pasa y la demostración completa funciona sin fallos conocidos que impidan el alcance.

### Fase 13 — Documentar, empaquetar y entregar

**Entrada:** aceptación superada. **Salida:** proyecto reproducible y defendible. **Dependencias:** fase 12. **Prioridad:** P1; necesaria para cerrar este plan.

- [ ] **T73 — Completar README reproducible.** Etiqueta: CALIDAD. Documentar JDK/Maven usados, construcción, pruebas, ejecución del JAR, ubicación de logs y solución de problemas. **Aceptación:** una persona puede iniciar sin conocer el historial del desarrollo.
- [ ] **T74 — Completar arquitectura y decisiones internas.** Etiqueta: CALIDAD. Documentar clases, paquetes, herencia, hilos, sincronización, estados y algoritmo, todo en Markdown. **Aceptación:** se explica por qué existe cada decisión y qué parte satisface del enunciado.
- [ ] **T75 — Completar manual y formatos.** Etiqueta: CALIDAD. Explicar controles, configuración, importaciones, mensajes de error, protocolo y extras. **Aceptación:** se incluyen archivos de ejemplo y se diferencia Stop de pausa y reset.
- [ ] **T76 — Preparar evidencias verificables.** Etiqueta: CALIDAD. Capturar GUI, trazas por elevador, recorridos simultáneos, reset y tres extras; vincularlas desde Markdown. **Aceptación:** cada captura/traza tiene contexto, pasos y resultado esperado; no se presenta como ejecutado un caso que solo se diseñó.
- [ ] **T77 — Construir el JAR ejecutable final.** Etiqueta: BASE. Ejecutar construcción limpia y verificar manifiesto y recursos. **Aceptación:** `java -jar target/elevator-manager-1.0.0.jar` abre la clase `ElevatorManager` una vez fijados esos nombre/versión en el POM.
- [ ] **T78 — Probar fuera del IDE.** Etiqueta: CALIDAD. Extraer una copia limpia en otra carpeta, compilar y ejecutar con JDK compatible. **Aceptación:** no depende de rutas absolutas del desarrollador ni archivos no incluidos; los logs se crean en ruta documentada y escribible.
- [ ] **T79 — Preparar paquete de entrega y demostración.** Etiqueta: CALIDAD. Reunir fuentes `.java` completas, POM, JAR, ejemplos, documentación `.md` y evidencias, según el medio solicitado por el docente. **Aceptación:** no faltan dependencias de ejecución; se excluyen cachés, credenciales y temporales.
- [ ] **T80 — Cerrar revisión contra el enunciado.** Etiqueta: CALIDAD. Revisar matriz, ejecutar guion final y etiquetar versión en Git. **Aceptación:** base y tres extras demostrados, documentación consistente y todas las tarjetas cerradas con evidencia; si algo queda abierto, declararlo explícitamente.

## 7. Diseño del algoritmo de asignación

Se propone un recorrido colectivo por sentido, inspirado en LOOK, y selección por tiempo estimado de recogida. Es una estrategia implementable y explicable, no una garantía matemática de mínimo global.

Para cada solicitud externa:

1. Leer snapshots coherentes de las cabinas disponibles.
2. Para cada cabina, proyectar su agenda respetando el destino/frontera comprometidos, paradas internas y sentido de las llamadas.
3. Estimar el tiempo hasta poder detenerse en el origen con una dirección de servicio compatible: sumar el tramo restante, desplazamientos posteriores y paradas anteriores. El tiempo de recogida se mide hasta abrir/iniciar la parada de recogida, sin sumar como espera del usuario el tiempo posterior de esa misma parada.
4. Elegir el menor tiempo estimado; desempatar por menor carga y luego menor ID.
5. Confirmar que la cabina sigue disponible, incorporar la llamada de forma segura y registrar asignación y costo.
6. Al llegar a una parada, confirmar qué solicitudes se atendieron; conservar pendientes incompatibles.

Un recorrido no debe invertir prematuramente por una llamada externa detrás de la cabina. En la frontera de inversión, una cabina puede recoger una llamada de sentido contrario para iniciar su regreso: definir y registrar primero la dirección de servicio correcta. Los destinos internos son paradas aunque no tengan dirección solicitada.

**Ejemplo de prueba:** A está en piso 2 subiendo hacia 8 y B está libre en 5; llega una llamada en 3 hacia abajo. A está más cerca por distancia, pero no puede dar la vuelta inmediatamente. La estimación debe considerar que termina su recorrido, por lo que B puede recoger antes. Documentar tiempos y agenda exactos antes de afirmar el resultado.

Registrar espera hasta recogida, número de tramos recorridos y número de paradas en escenarios controlados ayuda a demostrar eficiencia. No introducir penalizaciones arbitrarias sin unidades o justificación. La equidad ante un flujo infinito de nuevas llamadas no está especificada; documentar la limitación y comprobar al menos que un lote finito termina.

## 8. Matriz de pruebas de aceptación

| ID | Escenario | Resultado esperado | Tareas principales |
| --- | --- | --- | --- |
| A01 | Iniciar con N cabinas | Todas en piso 1/arriba; entrada `ElevatorManager`. | T11, T16–T19, T47 |
| A02 | Configuración inválida | Mensaje claro, sin arranque parcial. | T16, T47 |
| A03 | N = 1, P = 1 | Aplicación estable; no llamadas externas imposibles. | T16, T49, T58 |
| A04 | Llamada externa válida | Manejador asigna y se recoge en el sentido correspondiente. | T29–T33, T49 |
| A05 | Destino interno | Se enciende, se visita y se apaga. | T24–T26, T50 |
| A06 | Ver piso a mitad de tramo | Conserva el último piso dejado, aunque la animación avance. | T23, T59 |
| A07 | Dos cabinas con trabajo | Movimientos concurrentes y estados independientes. | T22, T28 |
| A08 | Llamada detrás mientras sube | No invierte antes de completar compromiso. | T25, T33 |
| A09 | Llamada compatible intermedia | Se incorpora sin llegar primero al extremo y regresar. | T25, T33 |
| A10 | Más cercano ocupado | Se comparan tiempos y se justifica la selección. | T30, T31, T68 |
| A11 | Dos sentidos en el mismo piso | Atender uno no cancela indebidamente el otro. | T24, T32, T33 |
| A12 | Importar al inicio y tras reset | Valida/prepara lote; Start ejecuta. | T43–T46 |
| A13 | Importar fuera de RESET | Operación rechazada sin alterar ejecución. | T45 |
| A14 | Error al final de un archivo | Ninguna línea del lote se aplica parcialmente. | T44, T46 |
| A15 | Reset desde movimiento/parada | Descarta pendientes viejos, retorna físicamente y queda arriba. | T37, T38, T40 |
| A16 | Stop, Start y pulsaciones repetidas | No avance indebido, pérdida ni duplicación de trabajadores. | T35, T36, T40 |
| A17 | Registros por cabina | Los cinco tipos obligatorios en terminal y archivo correcto con ID. | T41, T42 |
| A18 | Muchas solicitudes y cabinas | GUI responde y no hay corrupción/interbloqueo. | T52, T69 |
| A19 | Pausar a mitad de tramo/parada | Estado congelado; Reanudar consume tiempo restante. | T54–T57 |
| A20 | Animación y retorno por reset | Cabinas y pisos identificables, desplazamiento coherente. | T58–T61 |
| A21 | Protocolo textual real | Orden cruza como cadena, se valida, se acepta y se completa. | T62–T66 |
| A22 | Mensaje de generación anterior | Se descarta y no contamina el estado tras reset. | T38, T65, T66 |
| A23 | Cerrar durante pausa/reset | Trabajadores terminan y archivos se liberan. | T39, T56 |
| A24 | Construir/ejecutar copia limpia | Verificación y JAR funcionan fuera del IDE. | T77, T78 |
| A25 | Lote finito completo | Toda solicitud válida termina o consta cancelada por una acción explícita. | T32, T67, T69 |

Para pruebas de tiempo usar reloj monotónico, tolerancias explícitas y límites de espera. Para concurrencia preferir sincronización observable (`CountDownLatch`, condiciones o eventos) a depender de `sleep` arbitrarios. Para la GUI, combinar pruebas del modelo/controlador con inspección manual; no incorporar automatización visual compleja si no aporta valor al alcance.

## 9. Propuesta inicial del protocolo y del archivo

### 9.1 Archivo de solicitudes

Ejemplo para un edificio con al menos 8 pisos y 2 cabinas:

```text
# Archivo UTF-8; las solicitudes se preparan en RESET y se emiten en orden al iniciar.
HALL 3 UP
HALL 7 DOWN
CABIN 1 8
CABIN 2 4
```

`CABIN` es una selección sintética del usuario dentro de la cabina; no afirma que se haya simulado una persona que subió físicamente. Si se requiere un guion con retardos o pasajeros, eso sería una ampliación que el enunciado no exige. El parser puede reutilizar validadores del protocolo, pero el archivo no necesita contener ACK ni eventos.

### 9.2 Mensajes entre manejador y elevadores

Ejemplo conceptual que se debe formalizar en T62:

```text
V1 CMD 42 EPOCH 3 ELEVATOR 2 PICKUP 7 DOWN
V1 ACK 42 EPOCH 3 ELEVATOR 2 ACCEPTED
V1 EVENT 42 EPOCH 3 ELEVATOR 2 PICKUP_COMPLETED 7 DOWN
V1 CMD 43 EPOCH 3 ELEVATOR 2 DESTINATION 4
V1 CMD 44 EPOCH 4 ELEVATOR 2 RESET
V1 ERROR 45 EPOCH 4 ELEVATOR 2 INVALID_FLOOR
```

El receptor debe definir tokens exactos, argumentos, errores y mensajes de estado; los ejemplos no sustituyen la gramática. `EPOCH` invalida trabajo anterior y debe coordinarse en todo el sistema. No se necesitan sockets: colas de cadenas entre hilos satisfacen el transporte textual si los comandos se serializan y parsean de verdad.

## 10. Riesgos concretos y controles

| Riesgo | Consecuencia | Control previsto |
| --- | --- | --- |
| Hacer movimiento en EDT | Ventana congelada. | Trabajadores para motor, lectura y cierre; EDT solo presenta. |
| Usar una sola FIFO como ruta | Paradas ineficientes e inversiones indebidas. | Cola de entrada separada de agenda ordenada por sentido. |
| Limpiar solo listas en reset | Reaparecen comandos que estaban en vuelo. | Generación de simulación y confirmaciones de todas las cabinas. |
| `Thread.stop()` o `suspend()` | Estado corrupto o bloqueos. | Cancelación cooperativa, condiciones e interrupciones controladas. |
| Reanudar un `sleep` desde cero | Se altera el tiempo de simulación. | Tiempo restante y reloj monotónico. |
| Duplicar handlers o usar padre sin control | Logs repetidos o mezclados. | Configuración única y cierre de handlers. |
| Importar línea por línea al motor | Lote parcialmente aplicado ante error. | Parseo/validación completa y confirmación atómica. |
| Considerar ACK como recogida | El manejador pierde seguimiento. | Distinguir aceptación, ejecución y finalización. |
| Tratar detener como dirección | Se pierde el sentido del recorrido. | Estados operativos separados de dirección. |
| Reducir el extra gráfico a etiquetas | Extra de animación sin demostrar. | Edificio y cabinas realmente animados. |

## 11. Entregables y condición de cierre

| Entregable | Contenido |
| --- | --- |
| Código | Todas las clases `.java` completas en paquetes con herencia funcional y trabajadores independientes. |
| Construcción | `pom.xml` con versiones fijadas, pruebas y manifiesto para `ElevatorManager`. |
| Ejecutable | JAR ejecutable que arranca fuera del IDE en entorno Java compatible con escritorio. |
| Ejemplos | Archivos válidos e inválidos de solicitudes con descripción de sus escenarios. |
| Documentación Markdown | Análisis/plan, decisiones, arquitectura, asignación, protocolo, pruebas, manual y evidencias. |
| Evidencias | Capturas y trazas de comportamiento base y de los tres extras, vinculadas al guion reproducible. |
| Registros de muestra | Archivos diferenciados de elevadores de una ejecución identificada. |

**El proyecto queda terminado cuando:** se cumplen los requisitos base, pasan las pruebas de aceptación relevantes, funcionan los tres extras, se puede construir y ejecutar desde una copia limpia, y la documentación explica lo que efectivamente se entregó. La asignación de puntaje por extras la determina el docente; este documento no inventa porcentajes.

## 12. Referencias técnicas consultadas

El análisis de obligaciones proviene exclusivamente del enunciado adjunto. Las fuentes siguientes respaldan las herramientas propuestas:

- [Java SE 17: Swing](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/swing/package-summary.html).
- [Java SE 17: módulo java.desktop](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/module-summary.html).
- [Java SE 17: Logger](https://docs.oracle.com/en/java/javase/17/docs/api/java.logging/java/util/logging/Logger.html).
- [Java SE 17: módulo java.logging](https://docs.oracle.com/en/java/javase/17/docs/api/java.logging/module-summary.html).
- [Instalación de Maven](https://maven.apache.org/install).
- [Guía de inicio de Maven](https://maven.apache.org/guides/getting-started/index.html).

## 13. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 1.0.0 | 2026-10-19 | Análisis inicial, decisiones propuestas, arquitectura y 80 tareas en 14 fases, incluyendo tres extras y cierre. |

## 14. Integración con la documentación previa al código

Esta copia adapta el plan original a la ruta canónica `docs/01-analisis-y-plan.md`, conservando sus 80 tareas y sus IDs. No se ha ejecutado ni aprobado ninguna tarea por el hecho de redactar los documentos.

- [02 — Decisiones](02-decisiones.md): semántica de controles, estados y límites.
- [03 — Arquitectura](03-arquitectura.md): responsabilidades, herencia, hilos y contratos.
- [04 — Algoritmo](04-algoritmo-asignacion.md): barridos, ETA y casos teóricos.
- [05 — Protocolo](05-protocolo.md): gramática textual y archivo de solicitudes.
- [06 — Pruebas](06-pruebas.md): plan previo, sin resultados ejecutados.

Los documentos 02..06 son propuestas versión 0.1.0. Detallan las decisiones inicialmente sugeridas aquí; ante un detalle no especificado en el plan, consultar el documento especializado. Un conflicto futuro debe corregirse en ambos archivos y registrarse, no dejarse a interpretación del implementador.

Refinamientos actuales: agregar PAUSING/CLOSING como estados transitorios; Stop completa el tramo iniciado sin abrir servicio nuevo y congela una parada ya iniciada; completar una solicitud significa iniciar su servicio; rechazar importaciones vacías; definir frontera de barrido y protocolo completo. El manual 07 se redactará sobre la GUI real y las evidencias 08 sobre ejecuciones verificadas. No se crean como si el programa ya existiera.

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 1.0.1 | 2026-10-19 | Copia para docs, enlaces a especificaciones previas al código y precisión de estados/controles. |

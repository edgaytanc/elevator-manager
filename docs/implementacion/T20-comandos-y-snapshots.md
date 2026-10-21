# T20 — Implementar comandos y snapshots

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** implementación preparada; compilación y comprobaciones locales correctas; integración Maven pendiente en el equipo del usuario.
- **Base:** arquitectura T12; identidad e inmutabilidad T13/T18; publicación T14; invariantes T15; modelo T16–T19; propuesta textual 05.

## 1. Resultado y archivos

| Archivo | Ruta relativa desde la raíz del proyecto | Acción |
| --- | --- | --- |
| Command.java | src/main/java/com/elevatormanager/protocol/Command.java | Nuevo. |
| ElevatorSnapshot.java | src/main/java/com/elevatormanager/model/ElevatorSnapshot.java | Nuevo. |
| ElevatorState.java | src/main/java/com/elevatormanager/model/ElevatorState.java | Sustituir archivo completo; agrega snapshot(long). |
| CommandTest.java | src/test/java/com/elevatormanager/protocol/CommandTest.java | Nuevo. |
| ElevatorSnapshotTest.java | src/test/java/com/elevatormanager/model/ElevatorSnapshotTest.java | Nuevo. |
| Este documento | docs/implementacion/T20-comandos-y-snapshots.md | Nuevo. |

Extraer el ZIP en `D:\Users\David\Desktop\workspace\java\elevator-manager`, conservando rutas y aceptando la sustitución de ElevatorState.java. La sustitución mantiene todos los contratos y el comportamiento previo de T19. No modifica POM, clases de solicitudes, ventana ni pruebas anteriores.

## 2. Comandos tipados

Command es una interfaz sellada de Java 17 con tres records anidados, todos inmutables:

| Tipo | Datos | Significado |
| --- | --- | --- |
| Command.Pickup | commandId, elevatorId, HallRequest | Orden de recogida externa ya asignada. |
| Command.Destination | commandId, elevatorId, CabinRequest | Orden para destino interno de la cabina indicada. |
| Command.Reset | commandId, epoch, elevatorId | Control que propone nueva época y retorno físico. |

Todos exponen commandId(), epoch() y elevatorId(). Los IDs de comando son long positivos, independientes del UUID de solicitud. Las órdenes de servicio derivan la época de su solicitud; no mantienen una segunda copia que pueda divergir. Destination rechaza destinatario distinto de la cabina de la solicitud.

Los constructores comprueban estructura. validateAgainst comprueba destinatario dentro del edificio y usa la validación polimórfica de la solicitud; devuelve errores comunes de destinatario antes de los del contenido. No comprueba estado global, disponibilidad, época activa ni autorización de reset.

sameContent compara tipo, ID de comando, época, destinatario y contenido operativo: UUID/piso/sentido para recogida; UUID/piso para destino. createdAt es trazabilidad y no forma parte de ese contenido operativo. La igualdad automática de los records no sustituye esta comprobación: Request.equals compara solo UUID. El futuro registro de comandos debe usar sameContent para detectar ID_CONFLICT.

La asignación de IDs crecientes y únicos, el registro de reintentos y las confirmaciones se conectarán con el emisor/receptor. Crear una orden no ejecuta movimiento, no limpia agendas y no produce ACK ni COMPLETED. Reset no es un subtipo de Request.

### 2.1 Relación con el protocolo textual

T20 implementa el contrato tipado del dominio/transporte interno. No implementa codificador, parser ni colas de cadenas y no acredita todavía el extra textual. Las órdenes futuras se serializarán y parsearán realmente antes de actuar, conforme a 05 y T62–T66.

Hay precisiones pendientes del diseño textual inicial que se conservan explícitas: T13/T18 usan UUID de solicitud, mientras los ejemplos de 05 muestran números; el modelo acepta época/revisión 0, mientras 05 propone época inicial 1 y enteros positivos. Al formalizar el codec se deberá actualizar 05 para UUID canónico y revisión no negativa, y fijar el arranque de sesión en 1 o revisar esa propuesta. T20 mantiene el dominio ya validado de T16–T19; no interpreta silenciosamente UUID como número ni restringe sus constructores a época 1.

Los controles restantes y la correlación/orden de operación de T14 se agregarán en las tarjetas de ciclo de vida y protocolo. T20 cubre las órdenes de recoger, destino interno y reset exigidas por esta tarjeta, sin inventar un ejecutor paralelo.

## 3. Snapshot inmutable y coherente

ElevatorSnapshot es un record sin referencia al estado mutable. Incluye configuración inmutable, ID, época, revisión, piso actual/siguiente, dirección, fase, fase suspendida/motivo, modo normal o retorno por reset, duración/restante en nanosegundos, referencia monotónica, frontera comprometida y copias de pendientes.

Los enums auxiliares HoldReason y Mode están anidados en el snapshot. Distinguen STOP/PAUSE y NORMAL/RESET_RETURN sin mezclar estado global, dirección y fase. No realizan transiciones por sí solos.

| Regla | Validación |
| --- | --- |
| Cabina y piso | ID en 1..N, piso en 1..P, época/revisión no negativas. |
| Frontera | Ausente o piso válido del edificio. |
| MOVING | Siguiente piso adyacente en el sentido indicado y dentro del edificio; actual conserva el último piso dejado. |
| DWELLING | Sin siguiente piso de tramo. |
| HELD | Exige MOVING o DWELLING suspendida y motivo; conserva tiempo y geometría correspondientes. |
| Stop frente a pausa | STOP no puede suspender MOVING: debe acabar el tramo. PAUSE puede congelarlo. |
| Sin HELD | No admite fase suspendida ni motivo. |
| Duración | MOVING usa tiempo de movimiento configurado; DWELLING usa tiempo de parada; IDLE/TERMINATED usan cero. |
| Restante | Entre cero y duración; no se descuenta tiempo al leer. |
| Pendientes | Época vigente, datos válidos, destino interno propio y sin UUID o claves funcionales repetidos. |
| Retorno/terminación | Sin solicitudes de servicio pendientes. |

List.copyOf copia ambas agendas y sus elementos son las clases finales inmutables de T18. Modificar la colección de origen después de construir no modifica el snapshot. progress() devuelve el progreso capturado; no consulta System.nanoTime ni confirma llegadas. referenceNanos puede ser negativo porque un reloj monotónico no garantiza origen positivo.

La validación es de coherencia estructural: no acredita que transcurrió tiempo real, se cumplió una barrera o el planificador autorizó la ruta. Las operaciones del worker serán responsables de esos hechos. La frontera y agendas preparan la representación; el plan de barrido/diferidos para ETA se ampliará al implementar RoutePlanner. No se presenta este snapshot como un planificador terminado.

## 4. Captura y publicación

ElevatorState.snapshot(long referenceNanos) debe llamarse exclusivamente desde su hilo propietario. Construye una sola instantánea y no modifica revisión ni agendas. La versión actual del estado solo representa IDLE y pendientes; por ello publica sin tramo, suspensión ni compromiso activos y tiempos cero. Cuando se implementen operaciones físicas, este método se ampliará para capturar sus datos reales, sin sintetizar valores vacíos para una cabina en movimiento.

Capturar no hace thread-safe a ElevatorState. El propietario construye el snapshot y lo publica con una única escritura a AtomicReference<ElevatorSnapshot>; el lector obtiene esa referencia una vez y usa sus componentes. La referencia atómica pertenecerá al worker/canal, no al estado mutable. No se añade publicación automática desde getters ni exposición de ElevatorState a Swing.

T20 comprueba esta forma de publicación en pruebas, sin crear los trabajadores definitivos antes de T22. No hay lectura simultánea de mapas mutables. Publicaciones visuales intermedias pueden omitirse más adelante; confirmaciones y servicios siguen requiriendo sus canales fiables, no simples snapshots.

## 5. Pruebas y evidencia

CommandTest contiene cuatro métodos: identidad/enrutamiento, estructura inválida, validación contextual y conflictos de contenido. ElevatorSnapshotTest contiene ocho: captura coherente, sustitución atómica con copias anteriores estables, copia defensiva, movimiento/progreso, suspensión, geometría, temporización y pendientes inválidos.

**Ejecutado aquí:** compilación Java 17 correcta de las incorporaciones y dependencias, con ElevatorState actualizado. Comprobador temporal independiente: 62 comprobaciones correctas, incluida publicación entre un hilo escritor propietario del estado y un lector mediante AtomicReference y CountDownLatch con esperas acotadas, sin sleep. No se ejecutaron Maven ni JUnit aquí por ausencia de Maven y del repositorio completo del usuario.

Desde PowerShell, en la raíz del proyecto:

```powershell
mvn '-Dtest=CommandTest,ElevatorSnapshotTest' test
mvn clean verify
git diff --check
```

Las comillas mantienen el filtro con coma como un solo argumento de PowerShell. **Esperado:** doce pruebas específicas y, conservando las treinta y tres anteriores, cuarenta y cinco pruebas totales; cero fallos/errores y BUILD SUCCESS. La base reportada de 23 fuentes de producción pasa a 25 archivos; los records/enums anidados generan clases adicionales sin aumentar la cantidad de archivos fuente. Habrá seis archivos de pruebas.

## 6. Cierre Git y siguiente tarea

Tras verificar integración, actualizar estado y evidencia con resultados reales. Preparar los archivos de esta tarjeta:

```powershell
git add -- src/main/java/com/elevatormanager/protocol/Command.java src/main/java/com/elevatormanager/model/ElevatorSnapshot.java src/main/java/com/elevatormanager/model/ElevatorState.java src/test/java/com/elevatormanager/protocol/CommandTest.java src/test/java/com/elevatormanager/model/ElevatorSnapshotTest.java docs/implementacion/T20-comandos-y-snapshots.md
git diff --cached --check
git diff --cached --stat
git commit -m "T20: implementar comandos tipados y snapshots inmutables"
git status --short
```

Revisar cambios ajenos ya preparados antes del commit. Cerrar T20 tras comprobar integración. Siguiente: T21 — Probar las reglas del modelo; será la revisión consolidada de aceptación de la fase 3, aunque ya existan pruebas por tarjeta.

## 7. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Comandos tipados, comparación de contenido, snapshot validado, captura del estado y pruebas. |

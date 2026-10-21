# T19 — Implementar el estado independiente de cada elevador

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** implementación preparada, compilación y comprobaciones locales correctas; integración Maven pendiente en el equipo del usuario.
- **Base:** T19 del plan; arquitectura T12; jerarquía T13/T18; propiedad del estado T14; invariantes T15; configuración T16 y tipos T17.

## 1. Resultado y límites de la tarjeta

ElevatorState es una clase final del paquete `com.elevatormanager.model`. Cada instancia tiene identidad, época, revisión, piso actual, dirección, fase y dos mapas privados de solicitudes. Cada mapa se crea por instancia: ninguna agenda es static ni se recibe como colección compartida.

Todos comienzan en piso 1, dirección UP, fase IDLE, revisión 0 y sin pendientes. Los constructores comprueban ID en 1..N y época no negativa. La misma SimulationConfig inmutable puede compartirse y suministra tiempos comunes.

T19 implementa el estado inicial y la incorporación segura de pendientes locales. La independencia se demuestra modificando agendas de cabinas distintas. Los campos físicos todavía no tienen operaciones de cambio: recibir una solicitud no mueve la cabina. No se agregan setters que permitan inventar una llegada o mezclar fase y dirección.

Los tramos, tiempos restantes, fase suspendida, servicio, frontera de barrido, retorno por reset y terminación se desarrollarán con sus operaciones en las tarjetas de snapshots, movimiento, planificación y ciclo de vida. Los campos privados de estado podrán ampliarse allí conservando los accesores actuales. No se simula reset cambiando directamente el piso a 1.

## 2. Propiedad y concurrencia

ElevatorState es mutable y **no es thread-safe**. Según T14, el futuro ElevatorWorker será su único propietario y el único que acceda a sus operaciones. No se entrega esta instancia a GUI, estrategia, manejador ni otros trabajadores. Su confinamiento es un contrato de integración: esta clase no comprueba identidad de hilo ni crea locks o trabajadores.

Las listas devueltas son copias inmutables hechas con List.copyOf, cuyos elementos son los tipos finales inmutables de T18. Eso evita referencias vivas a agendas, pero no autoriza llamar sus getters concurrentemente con una escritura. T20 introducirá los snapshots; la publicación atómica se conectará con los trabajadores conforme a T14.

La composición futura deberá crear exactamente una instancia por ID y garantizar IDs únicos por sesión. El constructor valida el rango local; no usa un registro static para imponer unicidad entre sesiones independientes.

## 3. Agendas e incorporación

| Elemento | Contrato |
| --- | --- |
| getCabinRequests | Copia de destinos internos dirigidos a esta cabina. |
| getHallRequests | Copia de recogidas externas previamente asignadas por el manejador. |
| Orden de listas | Orden de incorporación dentro de cada agenda; no es prioridad ni orden de ejecución. |
| addPendingRequest | Recibe Request y usa validateAgainst polimórficamente; después incorpora el subtipo en su agenda. |
| Resultado true | Nueva solicitud almacenada; revisión aumenta una vez. No implica llegada ni servicio. |
| Resultado false | UUID ya pendiente con exactamente el mismo contenido; no modifica agenda ni revisión. |
| Rechazo | Null produce NullPointerException; tipo desconocido, época distinta, datos contextuales inválidos o cabina equivocada producen IllegalArgumentException. |

La incorporación solo acepta HallRequest y CabinRequest. Esto evita almacenar una futura subclase desconocida cuya inmutabilidad no esté garantizada. Si la jerarquía se amplía, deberá adaptarse esta frontera explícitamente.

El llamador debe comprobar RUNNING/estado de ciclo de vida permitido y asignación antes de invocar esta operación. El estado local no recibe SimulationState, no decide qué cabina es óptima y no verifica por sí solo que el manejador efectivamente emitió una asignación externa.

La revisión usa Math.incrementExact antes de modificar el mapa. Si se agotara su rango se produce ArithmeticException sin introducir una nueva entrada; el futuro worker deberá comunicar la incidencia. No hay incremento en reintentos ni rechazos.

## 4. Identidad, duplicados y separación

Antes de agregar, se busca UUID en ambas agendas. Si existe, se compara tipo, época, piso, fecha y dato específico. El mismo UUID con otro contenido se rechaza: no basta Request.equals, porque en T18 compara solo identidad.

Un UUID diferente con la misma clave funcional activa también se rechaza localmente, sin reemplazar la solicitud canónica. En la admisión global, el controlador deberá responder como duplicado con referencia al trabajo anterior. Esta comprobación local es defensa ante una entrada inconsistente del llamador; no sustituye el registro global ni su respuesta al usuario.

Dentro de una cabina y época, la clave interna es piso y la externa es piso/sentido. Las llamadas UP y DOWN al mismo piso intermedio son distintas y pueden coexistir; un destino interno al mismo piso también es independiente. La clase no elige orden de servicio ni fusiona ambas direcciones.

Los duplicados de transporte seguirán teniendo control propio en el protocolo: reconocer una identidad lógica repetida aquí no implementa la correlación de mensajes de T20/T62–T66.

No se ofrece todavía una operación para retirar solicitudes como atendidas. Eso ocurrirá al iniciar servicio compatible y emitir su evento; agregar o llegar a un piso no puede apagar por sí solo sus pendientes.

## 5. Archivos e instalación

| Archivo | Ruta relativa desde la raíz del proyecto |
| --- | --- |
| ElevatorState.java | src/main/java/com/elevatormanager/model/ElevatorState.java |
| ElevatorStateTest.java | src/test/java/com/elevatormanager/model/ElevatorStateTest.java |
| Este documento | docs/implementacion/T19-estado-independiente-elevadores.md |

Extraer el ZIP en `D:\Users\David\Desktop\workspace\java\elevator-manager` conservando las rutas. Contiene únicamente estos tres archivos. No modifica POM, ventana ni clases de T16–T18.

## 6. Pruebas y evidencia

Doce métodos JUnit cubren estado inicial, independencia entre instancias, separación y orden de agendas, copias inmutables, construcción inválida, rechazos sin mutación, reintentos, conflictos UUID, duplicados funcionales, sentidos opuestos, edificio de un piso y subtipos desconocidos.

**Ejecutado aquí:** compilación de ElevatorState y dependencias de T16–T18 con objetivo Java 17, correcta. Un comprobador temporal independiente ejecutó 75 comprobaciones con Java 17.0.20, todas correctas. No se ejecutó JUnit ni Maven aquí por ausencia de Maven y de la copia completa del proyecto del usuario. No se acreditan pruebas paralelas con workers todavía inexistentes.

Desde PowerShell, en la raíz del proyecto:

```powershell
mvn -Dtest=ElevatorStateTest test
mvn clean verify
git diff --check
```

**Resultado esperado:** doce pruebas específicas y, conservando las veintiuna anteriores, treinta y tres pruebas totales; cero fallos/errores y BUILD SUCCESS. La base reportada de 22 fuentes de producción pasará a 23; habrá cuatro clases de pruebas. Cambios adicionales del usuario pueden alterar los conteos.

## 7. Cierre Git y siguiente tarea

Tras verificar la integración, actualizar estado y evidencia de este documento. Revisar y registrar los archivos de la tarjeta:

```powershell
git add -- src/main/java/com/elevatormanager/model/ElevatorState.java src/test/java/com/elevatormanager/model/ElevatorStateTest.java docs/implementacion/T19-estado-independiente-elevadores.md
git diff --cached --check
git diff --cached --stat
git commit -m "T19: implementar estado independiente y agendas por elevador"
git status --short
```

Revisar cambios ajenos ya preparados antes del commit. Cerrar T19 cuando la integración se compruebe. Siguiente: T20 — Implementar comandos y snapshots.

## 8. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Estado inicial por cabina, agendas propias, validación local y pruebas de independencia. |

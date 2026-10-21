# T18 — Implementar solicitudes con herencia

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** implementación preparada; compilación y comprobaciones locales correctas; integración Maven pendiente en el equipo del usuario.
- **Base:** T13 — Herencia de solicitudes; arquitectura T12; sincronización T14; invariantes T15; clases implementadas en T16/T17.

## 1. Objetivo y resultado

Implementar la relación «es una solicitud» con `Request` abstracta y dos clases finales derivadas. La clase base concentra datos comunes inmutables. `validateAgainst` es un método plantilla final: combina la validación común con el despacho especializado de `validateSpecific`. `describe` es abstracto y se resuelve según el subtipo real.

La prueba con una colección `List<Request>` usa ambos subtipos para validar y describir. Demuestra ejecución polimórfica de dominio; el flujo definitivo del importador/controlador se conectará en sus tarjetas. No se atribuye a T18 una GUI o un motor ya implementados.

## 2. Archivos nuevos

| Archivo | Ruta relativa |
| --- | --- |
| `ValidationError.java` | `src/main/java/com/elevatormanager/model/ValidationError.java` |
| `ValidationResult.java` | `src/main/java/com/elevatormanager/model/ValidationResult.java` |
| `Request.java` | `src/main/java/com/elevatormanager/model/request/Request.java` |
| `HallRequest.java` | `src/main/java/com/elevatormanager/model/request/HallRequest.java` |
| `CabinRequest.java` | `src/main/java/com/elevatormanager/model/request/CabinRequest.java` |
| `RequestTest.java` | `src/test/java/com/elevatormanager/model/request/RequestTest.java` |
| Este documento | `docs/implementacion/T18-solicitudes-con-herencia.md` |

Extraer el ZIP sobre `D:\Users\David\Desktop\workspace\java\elevator-manager`, conservando sus rutas. Solo incluye estos siete archivos. No modifica POM, clases anteriores ni documentos de diseño. Depende de SimulationConfig y Direction de T16/T17.

## 3. Contratos concretos

### 3.1 Construcción e inmutabilidad

`Request` tiene campos privados finales `UUID id`, `long epoch`, `int floor` e `Instant createdAt`. UUID e Instant son inmutables. El constructor protegido recibe los cuatro valores explícitos; los accesores `getId`, `getEpoch`, `getFloor`, `getCreatedAt` son finales. La clase base no llama métodos sobrescribibles desde su constructor.

HallRequest agrega únicamente Direction direction y CabinRequest únicamente int elevatorId. Ambas clases son finales; no tienen setters, estado de asignación ni campos de ejecución. Request permanece abstracta y su contrato exige que futuras extensiones también sean inmutables.

Los constructores rechazan null en referencias obligatorias mediante NullPointerException y época negativa, piso no positivo o ID de cabina no positivo mediante IllegalArgumentException. Estos errores estructurales se traducirán a diagnósticos de entrada en los adaptadores; no son el resultado de una validación contextual.

### 3.2 Validación contextual

`validateAgainst(SimulationConfig)` recibe configuración ya válida. Null se considera error de programación. Los rechazos ordinarios respecto al edificio devuelven ValidationResult y no lanzan excepciones.

| Código estable | Condición |
| --- | --- |
| `FLOOR_OUT_OF_RANGE` | Piso mayor que P; el constructor ya garantiza piso positivo. |
| `HALL_UP_AT_TOP` | Llamada UP en el último piso válido. |
| `HALL_DOWN_AT_BOTTOM` | Llamada DOWN en el primer piso. |
| `ELEVATOR_OUT_OF_RANGE` | Destino interno con ID mayor que N. |

Se conservan primero errores comunes y después específicos. Si el piso externo está fuera del edificio, HallRequest omite diagnósticos de dirección derivados de ese piso inválido. En CabinRequest, el ID inválido es independiente del piso: ambos errores pueden devolverse juntos y ordenados.

Para P=1, las dos llamadas externas son inválidas y un destino interno al piso 1 de una cabina existente es válido. Una solicitud al piso actual no se rechaza por ello: el trabajador decidirá cómo servirla al existir el estado físico.

ValidationError es un record de código/mensaje no nulos ni blancos. ValidationResult copia la lista con List.copyOf: no conserva una lista mutable del llamador, no admite elementos nulos y no permite modificar sus errores. Cada error es también inmutable. Un resultado sin errores es válido. combine conserva orden sin alterar los resultados de entrada.

### 3.3 Identidad y seguimiento

equals y hashCode son finales y usan únicamente UUID, incluso al comparar subtipos distintos. Una reutilización de UUID con otro contenido sigue representando identidad igual: el registro de admisión deberá detectar la inconsistencia y rechazarla antes de incorporar o sustituir trabajo. T18 no implementa ese registro.

Dos UUID distintos para una misma llamada no son iguales. Las futuras claves de duplicación funcional serán `(época,piso,sentido)` para externas y `(época,cabina,piso)` para internas. El UUID de solicitud tampoco es el ID de mensaje del protocolo.

Validar contra el edificio no comprueba RUNNING, época activa, duplicados ni disponibilidad de cabina. Esas reglas corresponden al controlador y receptores. Reset invalida solicitudes anteriores sin modificar sus campos. createdAt es trazabilidad; las duraciones físicas usarán reloj monotónico.

### 3.4 Dependencias y concurrencia

El dominio no depende de Swing, logger, buzones, estrategias ni trabajadores. describe produce texto humano; no serializa órdenes del protocolo. No se crean hilos en una solicitud: estos objetos inmutables podrán cruzar los canales entre el manejador y las cabinas. Las colecciones mutables que los contengan seguirán necesitando la protección de T14.

No se crean subtipos de Request para Reset/Stop/Pause/Close: son controles con otro ciclo de vida.

## 4. Pruebas y evidencia

Se incluyen once métodos JUnit Jupiter para polimorfismo, límites externos, edificio de un piso, prioridad de error común, acumulación de errores independientes, construcción inválida, referencias obligatorias, identidad UUID, metadatos, copias inmutables y diagnósticos válidos estructuralmente.

**Ejecutado aquí:** compilación de cinco clases nuevas y dos dependencias con Java 17 y `--release 17`, correcta. Comprobador temporal independiente: 107 comprobaciones correctas sobre llamadas, destinos, límites, descripciones, identidad, metadatos, encapsulación y rechazos. No se ejecutaron JUnit ni Maven aquí por ausencia de Maven y de la copia completa del repositorio del usuario.

Desde PowerShell, en la raíz:

```powershell
mvn -Dtest=RequestTest test
mvn clean verify
git diff --check
```

**Esperado:** once pruebas específicas correctas. Con las diez de T16/T17, veintiuna pruebas totales, cero fallos/errores y BUILD SUCCESS. La base reportada de 17 fuentes de producción pasa a 22; las clases de prueba pasan de dos a tres. Cambios adicionales del usuario podrían alterar esos conteos.

No se afirma que la deduplicación funcional, la admisión por época, la asignación ni el servicio físico estén implementados. Se probarán al conectar sus componentes.

## 5. Cierre Git

Después de verificar, actualizar estado y evidencia con los resultados reales. Preparar únicamente los archivos de esta tarjeta:

```powershell
git add -- src/main/java/com/elevatormanager/model/ValidationError.java src/main/java/com/elevatormanager/model/ValidationResult.java src/main/java/com/elevatormanager/model/request/Request.java src/main/java/com/elevatormanager/model/request/HallRequest.java src/main/java/com/elevatormanager/model/request/CabinRequest.java src/test/java/com/elevatormanager/model/request/RequestTest.java docs/implementacion/T18-solicitudes-con-herencia.md
git diff --cached --check
git diff --cached --stat
git commit -m "T18: implementar solicitudes con herencia y validacion polimorfica"
git status --short
```

Revisar cambios ajenos ya preparados antes del commit. T18 se cierra tras comprobar integración. Siguiente: T19 — Implementar el estado independiente de cada elevador.

## 6. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Jerarquía inmutable, método plantilla, diagnósticos, identidad UUID, pruebas y comprobación local. |

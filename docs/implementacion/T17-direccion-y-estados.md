# T17 — Implementar dirección y estados

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** implementación preparada y comprobada localmente; integración Maven pendiente en el equipo del usuario.
- **Base:** T17 del plan; D11 y semántica de controles; arquitectura T12; sincronización T14; invariantes y transiciones T15.

## 1. Objetivo y alcance

Separar tres conceptos mediante enums independientes del paquete `com.elevatormanager.model`, sin Swing, archivos ni estado mutable. T16 ya fue validada por el usuario con seis pruebas y `mvn clean verify` correcto.

| Tipo | Responsabilidad | Valores |
| --- | --- | --- |
| `Direction` | Sentido de recorrido/servicio. | UP, DOWN |
| `SimulationState` | Estado global de sesión. | RESET, RUNNING, STOPPING, STOPPED, PAUSING, PAUSED, RESETTING, CLOSING, CLOSED |
| `ElevatorPhase` | Fase local de una cabina. | IDLE, MOVING, DWELLING, HELD, TERMINATED |

El nombre `SimulationState` concreta en T17 el tipo de estado global previsto documentalmente. No se renombra `ElevatorState`: ese nombre queda reservado para el estado compuesto de cabina que se implementará en T19.

La separación de tipos impide pasar una fase donde una firma exige una dirección. No existe `Direction.STOPPED`, ni se representa una cabina en reposo mediante una dirección nula. Tanto IDLE/UP como IDLE/DOWN serán combinaciones válidas del estado compuesto. Estos enums no crean cabinas ni cambian por sí solos una dirección.

## 2. Contratos

### 2.1 Direction

- `floorDelta()` devuelve +1 para UP y -1 para DOWN.
- `opposite()` devuelve la dirección opuesta sin mutar ningún objeto ni autorizar inversión de recorrido.
- El consumidor debe comprobar pisos 1..P antes de aplicar desplazamientos y proteger su aritmética. Un delta no es una validación de límites ni un comando de movimiento.
- La regla de no invertir durante un tramo o antes de terminar el compromiso sigue perteneciendo al planificador y al trabajador.

### 2.2 SimulationState

`acceptsManualRequests()` solo devuelve true en RUNNING. `allowsRequestImport()` solo devuelve true en RESET. Son predicados del estado, no permisos completos: el coordinador deberá volver a comprobar estado vigente, valores, época y correlación de operación al admitir o confirmar trabajo.

STOPPING, PAUSING, RESETTING y CLOSING expresan operaciones pendientes. Sus destinos confirmados no se publican automáticamente por invocar un método en el enum. RESET exige limpieza y retorno a 1/UP; CLOSED exige terminación real y recursos liberados.

No se agrega una máquina de transiciones que permita saltar directamente a RESET o CLOSED sin confirmaciones. Los controles se implementarán en el controlador/motor conforme a T14 y T15. Tampoco se añade un estado de error global nuevo.

### 2.3 ElevatorPhase

IDLE significa alineada con un piso y sin tramo o parada activa; no garantiza que no exista agenda pendiente. El estado global y el modo local pueden impedir ejecutar esa agenda.

MOVING conserva como piso actual el último piso dejado; DWELLING representa servicio ya iniciado. HELD exige conservar en el futuro estado compuesto la fase suspendida, el motivo y el restante; si suspendió MOVING también conserva el siguiente piso y el progreso. El enum aislado no almacena esos datos ni implementa pausa.

TERMINATED indica fin del trabajador local. No basta que una cabina tenga esa fase para declarar CLOSED global.

## 3. Archivos y ubicación

| Archivo | Ruta relativa del proyecto |
| --- | --- |
| `Direction.java` | `src/main/java/com/elevatormanager/model/Direction.java` |
| `SimulationState.java` | `src/main/java/com/elevatormanager/model/SimulationState.java` |
| `ElevatorPhase.java` | `src/main/java/com/elevatormanager/model/ElevatorPhase.java` |
| `DirectionAndStatesTest.java` | `src/test/java/com/elevatormanager/model/DirectionAndStatesTest.java` |
| Este documento | `docs/implementacion/T17-direccion-y-estados.md` |

Extraer el ZIP en `D:\Users\David\Desktop\workspace\java\elevator-manager` conservando las rutas. Solo incorpora estos cinco archivos. No necesita modificar el POM, la ventana mínima ni SimulationConfig.

## 4. Pruebas y evidencia

Se incluyen cuatro métodos JUnit Jupiter: desplazamiento adyacente por dirección, inversión reversible, admisión manual para todos los estados e importación para todos los estados. La separación entre dirección/fase/estado queda expresada en los tipos Java; no se inventa un estado mutable auxiliar para simular T19 dentro de las pruebas.

**Ejecutado aquí:** compilación de los tres enums con `--release 17`, correcta. Un comprobador temporal independiente con Java 17.0.20 ejecutó 24 comprobaciones sobre dirección y reglas de admisión, todas correctas. No se ejecutó Maven ni JUnit aquí: este entorno no dispone de Maven ni de la copia completa del repositorio del usuario.

Desde PowerShell, en la raíz del proyecto:

```powershell
mvn -Dtest=DirectionAndStatesTest test
mvn clean verify
git diff --check
```

**Esperado:** cuatro pruebas específicas correctas. Si únicamente están las seis pruebas de T16 y estas cuatro, la verificación completa ejecutará diez; ambas construcciones deberán terminar en BUILD SUCCESS, sin fallos ni errores. La compilación incluirá tres nuevos archivos de producción (17 en total si se conserva la base reportada en T16).

T17 no acredita movimiento, conservación efectiva de dirección al detener el motor, pausa física ni barreras globales: esas conductas deberán probarse al existir el estado compuesto y los trabajadores.

## 5. Cierre Git

Tras validar, actualizar el estado y la evidencia de este documento con los resultados reales. Revisar y registrar únicamente los archivos de esta tarjeta:

```powershell
git add -- src/main/java/com/elevatormanager/model/Direction.java src/main/java/com/elevatormanager/model/SimulationState.java src/main/java/com/elevatormanager/model/ElevatorPhase.java src/test/java/com/elevatormanager/model/DirectionAndStatesTest.java docs/implementacion/T17-direccion-y-estados.md
git diff --cached --check
git diff --cached --stat
git commit -m "T17: implementar direccion y estados separados"
git status --short
```

Revisar cambios ajenos ya preparados antes de hacer commit. Cerrar T17 tras verificar integración. Siguiente tarea: T18 — Implementar solicitudes con herencia (`Request`, `HallRequest`, `CabinRequest`) conforme a T13.

## 6. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Tres enums independientes, predicados de admisión, pruebas y verificación local Java 17. |

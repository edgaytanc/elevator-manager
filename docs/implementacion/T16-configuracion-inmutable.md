# T16 — Implementar configuración inmutable

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** implementación preparada; compilación y comprobaciones locales correctas; integración Maven en el proyecto de David pendiente.
- **Base:** enunciado; T16 del plan; D06/D07; diseño T12–T15.

## 1. Contexto recuperado

Las fases 1 y 2 están cerradas según confirmación del usuario. T10 preparó paquetes Maven; T11 validó la entrada `com.elevatormanager.app.ElevatorManager`, Swing en EDT y cierre por botón/X. T12–T15 documentaron componentes, herencia, sincronización y transiciones. Continuamos una tarea por turno, con código completo, archivos descargables, validación y cierre Git después de comprobar resultados.

El proyecto objetivo es `D:\Users\David\Desktop\workspace\java\elevator-manager`, con nivel Java 17, Maven y JUnit Jupiter. No se presupone una copia local de ese repositorio: este paquete contiene únicamente las incorporaciones de T16.

## 2. Contrato implementado

`com.elevatormanager.model.SimulationConfig` es un `record` público. Java 17 lo admite; es final y sus componentes primitivos privados son finales. No contiene colecciones, referencias mutables ni setters. Los accesores se llaman como los componentes, por ejemplo `floorCount()`.

| Componente | Tipo | Rango |
| --- | --- | --- |
| `elevatorCount` | `int` | 1..2147483647 |
| `floorCount` | `int` | 1..2147483647 |
| `movementTimeMillis` | `long` | 1..9223372036854 ms |
| `dwellTimeMillis` | `long` | 1..9223372036854 ms |

Los límites de cantidades son de representación, no garantías de que la máquina pueda ejecutar esa cantidad de trabajadores o dibujar tantos pisos. Construir la configuración no reserva cabinas ni pisos.

El máximo temporal es `Long.MAX_VALUE / 1_000_000L`. Esta precisión de implementación permite convertir una duración individual a nanosegundos sin saturación ni desbordamiento. Los módulos futuros deberán proteger también las sumas y productos de ETA; este máximo no garantiza que sumar recorridos arbitrarios sea seguro.

El constructor compacto aplica siempre las reglas, incluso con construcción directa. `fromText` centraliza la conversión para la futura GUI: acepta espacios alrededor de enteros decimales; rechaza null, blanco, texto no numérico, decimales, separadores de miles, notación científica y desbordamientos. `Long.parseLong` admite un signo inicial. Todos los rechazos se comunican mediante `IllegalArgumentException` con el nombre del campo. Se informa el primer error, sin crear una configuración parcialmente válida.

N=1 y P=1 son válidos. Con P=1, la validación de solicitudes externas imposibles corresponderá a T18. No se crea un hilo para almacenar configuración: la misma instancia inmutable podrá compartirse entre los trabajadores previstos en el diseño.

## 3. Archivos e instalación

| Archivo | Destino relativo desde la raíz del proyecto |
| --- | --- |
| `SimulationConfig.java` | `src/main/java/com/elevatormanager/model/SimulationConfig.java` |
| `SimulationConfigTest.java` | `src/test/java/com/elevatormanager/model/SimulationConfigTest.java` |
| Este documento | `docs/implementacion/T16-configuracion-inmutable.md` |

Extraer el ZIP sobre la raíz del proyecto conservando sus rutas. Contiene estos tres archivos y no modifica `pom.xml`, `ElevatorManager`, `MainWindow` ni los documentos anteriores. La configuración gráfica se conectará al implementar el formulario correspondiente.

## 4. Verificación

Ejecutar en PowerShell desde la raíz:

```powershell
mvn -Dtest=SimulationConfigTest test
mvn clean verify
git diff --check
git status --short
```

**Resultado esperado:** seis métodos de prueba JUnit correctos, cero fallos/errores y `BUILD SUCCESS` en ambas ejecuciones Maven. El comando completo también verifica las pruebas existentes.

Las pruebas cubren mínimos, conservación de valores, espacios, constructor directo, campos vacíos/nulos, sintaxis inválida en cada campo, cero/negativos, fronteras numéricas y desbordamientos. Solo emplean JUnit Jupiter API, sin pruebas parametrizadas ni dependencias adicionales. No abren Swing ni crean trabajadores.

**Resultado local real:** compilación del archivo de producción con `--release 17` y Java 17.0.20, correcta. Un comprobador temporal independiente ejecutó 63 comprobaciones de límites, rechazos e inmutabilidad, todas correctas. Ese comprobador es evidencia auxiliar de desarrollo; no sustituye JUnit. No se ejecutó Maven ni la suite JUnit aquí porque Maven no está instalado y no hay una copia del proyecto completo. No se afirma `BUILD SUCCESS` en el equipo del usuario.

## 5. Cierre y siguiente tarea

Después de obtener los resultados esperados, registrar únicamente los archivos de T16:

```powershell
git add src/main/java/com/elevatormanager/model/SimulationConfig.java src/test/java/com/elevatormanager/model/SimulationConfigTest.java docs/implementacion/T16-configuracion-inmutable.md
git diff --cached --check
git diff --cached --stat
git commit -m "T16: implementar configuracion inmutable y validaciones"
git status --short
```

No incluir otros cambios ya preparados sin revisarlos. Actualizar esta evidencia con el resultado real antes del commit cuando corresponda. T16 se cierra tras comprobar la integración; T17 será implementar dirección y estados, sin mezclar estado global con dirección o fase física. T18 implementará la herencia verdadera de solicitudes diseñada en T13.

## 6. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Configuración inmutable, validación textual, pruebas y comprobación local Java 17. |

# T21 — Probar las reglas del modelo

- **Proyecto:** Elevator Manager.
- **Fase:** 3 — Implementar el modelo y validar entradas.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Estado:** revisión consolidada y siete escenarios nuevos ejecutados localmente; verificación Maven de la entrega pendiente en el equipo del usuario.
- **Base:** aceptación de T21; plan de pruebas 06; contratos T12–T15; clases y pruebas de T16–T20.

## 1. Objetivo y resultado de la revisión

Comprobar límites, herencia funcional, identidad, duplicados y encapsulación sin Swing ni esperas reales de simulación. Se revisaron las seis clases JUnit previas y sus 45 métodos. El usuario confirmó su ejecución completa mediante `mvn clean verify`, con cero fallos, errores u omitidas, a las 02:10:50 del 2026-10-21, Guatemala.

La revisión detectó oportunidades de comprobación conjunta: reglas de múltiples edificios pequeños, encadenamiento de tipos de T16–T20, conservación de trabajo existente ante rechazos, matriz de identidad de comandos, límites enteros combinados y publicación efectiva entre dos hilos. Los siete escenarios nuevos pasaron sin modificar producción. No se afirma cobertura del 100 % ni corrección del motor todavía inexistente.

## 2. Archivos de esta entrega

| Archivo | Ruta relativa desde la raíz | Función |
| --- | --- | --- |
| ModelRulesScenarios.java | src/test/java/com/elevatormanager/model/ModelRulesScenarios.java | Escenarios de aceptación independientes de JUnit. |
| ModelRulesTest.java | src/test/java/com/elevatormanager/model/ModelRulesTest.java | Siete métodos JUnit que ejecutan exactamente esos escenarios. |
| Este documento | docs/implementacion/T21-pruebas-reglas-modelo.md | Matriz, evidencia y criterio de cierre. |

Extraer el ZIP en `D:\Users\David\Desktop\workspace\java\elevator-manager` conservando rutas. No sustituye código anterior ni cambia el POM. Ambos archivos Java pertenecen a test: no se agrega una clase de diagnóstico al producto ni una nueva entrada de la aplicación.

## 3. Matriz consolidada de reglas

| Regla de T21 | Evidencia previa confirmada por el usuario | Ampliación de aceptación |
| --- | --- | --- |
| N/P mínimos, texto inválido y duraciones representables | SimulationConfigTest: 6 métodos. | Matriz de 18 configuraciones N=1..3/P=1..6 y límites máximos combinados. |
| Dirección separada de estado global y fase | DirectionAndStatesTest: 4 métodos. | Snapshot IDLE/DOWN válido: reposo no restablece UP. |
| Herencia y comportamiento polimórfico | RequestTest: 11 métodos. | Validación de ambas especializaciones a través de Request y cadena hasta snapshot. |
| Identidad, cabina destinataria y época | RequestTest, ElevatorStateTest y CommandTest. | Correlación de comandos y preservación de solicitud canónica ante conflicto. |
| Duplicados sin pérdida de trabajo previo | ElevatorStateTest: 12 métodos. | Rechazos con agenda poblada; revisión y snapshot permanecen iguales. |
| Contenido de comandos distinto de igualdad UUID | CommandTest: 4 métodos. | Cambiar ID de comando, cabina, época, UUID, piso, dirección o tipo rompe sameContent. |
| Encapsulación y copia inmutable | ElevatorSnapshotTest: 8 métodos. | Publicación real con AtomicReference entre propietario escritor y lector. |
| Pendientes y modos coherentes | ElevatorSnapshotTest y ElevatorStateTest. | UUID compartido entre agendas, duplicados funcionales, servicio pendiente en retorno/terminación. |

Las seis clases anteriores suman 45 pruebas; algunas participan en varias filas de la matriz. No se suman filas como si fueran casos independientes adicionales.

## 4. Siete escenarios nuevos

| ID | Método | Resultado esperado | Observado aquí |
| --- | --- | --- | --- |
| T21-C01 | buildingBoundaryMatrix | Para cada edificio pequeño, aceptar solo pisos, IDs y sentidos físicamente posibles. | Pasa. |
| T21-C02 | requestsCommandsAgendasAndSnapshots | Conservar correlación, tipo y pendientes hasta snapshot; otra cabina no cambia. | Pasa. |
| T21-C03 | populatedStateSurvivesRejectedInputs | Rechazar conflicto UUID, duplicado, cabina ajena, época anterior/futura y sentido imposible sin borrar trabajo. | Pasa. |
| T21-C04 | commandConflictMatrix | Reconocer reenvío exacto y detectar cada cambio operativo en ambos sentidos de comparación. | Pasa. |
| T21-C05 | snapshotsCrossThreadsWithoutSharingMutableState | Publicar snapshot completo; el anterior permanece estable e inmutable; el hilo termina. | Pasa. |
| T21-C06 | numericBoundariesRemainRepresentable | Aceptar límites de representación sin reservar N cabinas ni P pisos; conversión a nanos exacta. | Pasa. |
| T21-C07 | snapshotPendingRulesAndStationaryDirection | Mantener IDLE/DOWN y separar sentidos; rechazar pendientes contradictorios. | Pasa. |

Se usan UUID e instante fijos, sin aleatoriedad ni reloj de pared para resultados. Los fallos lanzan AssertionError explícito: las comprobaciones no dependen de habilitar `-ea`. La infraestructura de rechazo exige el tipo de excepción esperado.

C05 crea el ElevatorState dentro del hilo escritor, lo conserva allí y publica únicamente ElevatorSnapshot. El lector nunca accede al estado mutable. CountDownLatch coordina las dos publicaciones; cada espera y join tiene límite de cinco segundos y el finally libera la coordinación. No hay Thread.sleep ni simulación de viajes. Esta prueba acredita publicación de datos del modelo, no paralelismo del manejador y N cabinas del producto.

## 5. Evidencia real y ejecución pendiente

| Ejecución | Entorno y resultado |
| --- | --- |
| Regresión T16–T20 | Confirmada por salida del usuario: Java objetivo 17, Maven 3.8.6 en Windows, 45 pruebas y BUILD SUCCESS. |
| Compilación T21 local | Producción vigente de T16–T20 y ModelRulesScenarios compilados con `--release 17`, correcta. |
| Escenarios T21 locales | Java 17.0.20, Linux, `java.awt.headless=true`; siete escenarios correctos. Se ejecutaron los mismos métodos que invoca el adaptador JUnit. |
| JUnit/Maven T21 en este entorno | No ejecutados: Maven no está instalado. No se afirma BUILD SUCCESS local ni ejecución de las 52 pruebas. |
| Verificación completa en el equipo del usuario | Pendiente. |

Salida local obtenida:

```text
T21: 7 escenarios de aceptación correctos; sin Swing ni esperas de simulación.
```

Desde PowerShell en la raíz del proyecto:

```powershell
mvn -Dtest=ModelRulesTest test
mvn clean verify
git diff --check
```

**Esperado:** siete pruebas específicas y 52 totales, cero fallos, errores u omitidas y BUILD SUCCESS en ambas ejecuciones. La producción conserva sus 25 archivos fuente. Habrá ocho archivos de test: siete clases con @Test y ModelRulesScenarios como soporte. Surefire no ejecuta el main adicionalmente.

La evidencia reproducible de JUnit estará en `target/surefire-reports/`. No se incluyen informes Surefire inventados ni se agregan target/ o logs/ al repositorio.

## 6. Relación con el plan 06 y alcance pendiente

| Casos del plan 06 | Qué acredita la fase 3 | Qué sigue pendiente |
| --- | --- | --- |
| P01/P02 | Configuración y estado inicial de dominio, mínimos y rechazos. | Formulario, sesión global RESET y flujo completo de arranque. |
| P03 | Rechazo contextual y defensivo de pisos, sentidos e IDs inválidos. | Entrada gráfica y recepción de comandos por workers. |
| P07/P08/P09 | Agendas propias, deduplicación defensiva local y sentidos separados. | Unificación global, servicio efectivo y luces de interfaz. |
| P04/P05/P37 | Coherencia de datos de movimiento/suspensión construidos como snapshots. | Avance temporal, llegadas y pausa ejecutados por el motor. |
| P35 | Herencia funcional y dominio organizado en paquetes. | Manejador y N trabajadores reales del simulador. |
| P42/P44/P45 | Contratos tipados, comparación de contenido y controles de época del modelo. | Codec textual, registro de comandos y adopción autorizada de época. |

Por ello no se marca como pasada una fila completa de 06 que todavía incluya UI, motor, protocolo o ciclo de vida. Este documento complementa el plan y conserva esa distinción.

Limitaciones conocidas que se mantienen: el llamador serializa admisiones; ElevatorState no es thread-safe; su snapshot actual captura IDLE y agendas; las operaciones físicas ampliarán esa captura; sameContent es la comparación operativa de comandos, mientras equals de Request sigue siendo identidad UUID. La armonización del formato textual con UUID y revisión 0 está registrada en T20 y deberá cerrarse antes del codec. Ninguna de estas tareas futuras se presenta como ejecutada en T21.

## 7. Cierre de T21 y fase 3

T21 y la fase 3 podrán cerrarse cuando los siete escenarios nuevos pasen mediante JUnit, la regresión completa de 52 pruebas termine correctamente y la evidencia quede incorporada. No se presupone que el usuario ya realizó commits anteriores.

Después de validar, actualizar estado y resultado de este documento. Registrar únicamente los tres archivos de esta tarjeta:

```powershell
git add -- src/test/java/com/elevatormanager/model/ModelRulesScenarios.java src/test/java/com/elevatormanager/model/ModelRulesTest.java docs/implementacion/T21-pruebas-reglas-modelo.md
git diff --cached --check
git diff --cached --stat
git commit -m "T21: verificar reglas del modelo y aceptacion de fase 3"
git status --short
```

Revisar cambios ajenos ya preparados antes del commit. La siguiente tarea será T22, comienzo de la implementación de los trabajadores y el motor según el plan. No se inicia en esta entrega.

## 8. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Revisión de 45 pruebas previas, siete escenarios integrados, ejecución local y matriz de aceptación del modelo. |

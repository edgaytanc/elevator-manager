# T64 — Validación local y aceptación pendiente

- **Fecha:** 2026-11-02, Guatemala.
- **Base:** T63 aceptada por David en `6c8d7d3`, 1,002 pruebas totales, BUILD SUCCESS y Git limpio.
- **Estado:** implementada y verificada de forma independiente; Maven Windows y aceptación pendientes.

## Evidencia disponible

| Criterio | Resultado ejecutado |
| --- | --- |
| Compilación | 128 fuentes independientes reales; OpenJDK 17.0.20, release 17/UTF-8. |
| T64 específica | 48 escenarios correctos con actores reales; 48 adaptadores JUnit equivalentes. |
| Regresión previa | 957 escenarios, 46 grupos correctos; 1,005 total incluyendo T64. |
| Recepción real | Modificar el piso del texto cambia el piso servido; Command/Request se reconstruyen, no son los originales. |
| Hilo de parseo | ProtocolReception confirma elevator-worker-N, sin crear un hilo por mensaje. |
| Errores | Entrada malformada/fuera de contexto no muta agenda; ERROR correlacionado y actor recuperable. |
| Concurrencia | FIFO por productor, snapshot condicional, cuatro productores, IDs conservados y joins. |
| Respuestas | ACK/ERROR, eventos físicos y terminales reales en colas de String; observadores no consumen eventos nativos. |
| Reset/cierre | Buzón prioritario textual, cancelación de activos, descarte de inbox viejo y futuro de retorno físico. |
| Compatibilidad | Firmas anteriores serializan; motor, herencia Request/HallRequest/CabinRequest, snapshots, GUI y reloj se conservan. |
| Demo | Actores reales desde clases y JAR auxiliar: SERVED, UNKNOWN_COMMAND y trabajador vivo. |
| Entrega | Código completo de ocho archivos en Markdown y todos los Java del proyecto en ZIP. |
| Calidad de archivos | diff --check, enlaces locales, bloques completos y ZIP íntegro comprobados. |

Los errores inyectados de las pruebas de logging no son fallos de la entrega; sus procesos terminaron con código 0. No se observó en T64 el timeout de GUI registrado en la entrega T63. Después del último ajuste de cierre se repitieron sus grupos afectados y las 48 específicas. [Resultados y traza completos](T64-resultados-locales.md).

## Comandos de validación en Windows

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
mvn "-Dtest=TextTransportTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.TextTransportDemo
git diff --check
git status --short
```

| Validación | Esperado | Estado recibido |
| --- | --- | --- |
| TextTransportTest | 48, sin fallos/errores/omitidas | Pendiente de David |
| Maven completo | 1,050, sin fallos/errores/omitidas; BUILD SUCCESS | Pendiente de David |
| Demo | reconstructed=true, sameContent=true, UNKNOWN_COMMAND, SERVED y T64_TRANSPORT_OK | Pendiente de David |
| Revisión de T64 | Sin incidencias abiertas de esta tarjeta | Pendiente |
| Commit y árbol limpio | Registrar hash real | Pendiente |

## Límites de la aceptación

Maven no está disponible en el entorno de implementación. Los 1,005 escenarios independientes no se presentan como ejecución de Surefire ni como las 1,050 pruebas esperadas. Los adaptadores JUnit no se compilaron aquí con sustitutos de sus dependencias; se ejecutarán con Maven en el equipo de David.

El seguimiento del manejador y las barreras conservan canales tipados mientras se integran en T65. STATE/STATUS y los controles adicionales como texto siguen pendientes, al igual que la unificación de autoridad/idempotencia histórica por sesión/generación. La GUI conserva los controles existentes y la publicación temporal precisa. T64 prueba la frontera textual real; no declara todo el extra 3 finalizado ni adelanta la aceptación T65/T66.


## Confirmación recibida de David — 2026-11-02

T64 cerrada: TextTransportTest 48 y suite completa 1,050, cero fallos/errores/omitidas y BUILD SUCCESS. Demo T64_TRANSPORT_OK, recepción real en elevator-worker-1, SERVED y recuperación de UNKNOWN_COMMAND. Git sin errores de diff/índice, commit `36af413`, árbol limpio. Los pendientes anteriores describen el estado de la entrega original y quedan sustituidos por esta evidencia.

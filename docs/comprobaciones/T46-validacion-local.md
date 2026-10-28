# T46 — Validación local

> Actualización al iniciar T47: David confirmó el cierre de las fases 1–7. La base adjunta contiene el commit `84f6261` de T46; se recuperó evidencia de 578 pruebas con BUILD SUCCESS. El contenido siguiente conserva el informe original previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T45 validada por David en `4f99454`, 40 pruebas específicas y 546 acumuladas, BUILD SUCCESS.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no está instalado.
- **Resultado:** producción y escenarios independientes compilados con objetivo Java 17; 533 escenarios satisfactorios en 27 grupos, incluidos los 32 nuevos de T46.

## Regresión independiente

Se compiló desde los fuentes actuales con el módulo jdk.compiler, sin utilizar target ni las clases del ZIP. El classpath incluyó src/main/resources para leer los ejemplos. Los adaptadores JUnit no se ejecutaron con sus dependencias reales; requieren Maven en el equipo de David.

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.511 |
| ElevatorSelectionScenarios | 18 | 0 | 1.637 |
| ManagerWorkerScenarios | 12 | 0 | 1.254 |
| PendingConfirmationScenarios | 26 | 0 | 1.712 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.287 |
| ScalabilityScenarios | 13 | 0 | 4.806 |
| ReadFailureScenarios | 12 | 0 | 0.131 |
| RequestFileScenarios | 48 | 0 | 0.407 |
| LoggingScenarios | 16 | 0 | 0.488 |
| SessionLoggingScenarios | 24 | 0 | 2.114 |
| ModelRulesScenarios | 7 | 0 | 0.150 |
| CabinResetScenarios | 32 | 0 | 7.431 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.284 |
| DirectionalAgendaScenarios | 20 | 0 | 3.466 |
| ElevatorDwellScenarios | 15 | 0 | 0.758 |
| ElevatorMovementScenarios | 10 | 0 | 0.240 |
| ElevatorWorkerScenarios | 7 | 0 | 0.264 |
| ExecutionEventScenarios | 20 | 0 | 1.043 |
| GlobalResetScenarios | 33 | 0 | 8.133 |
| ImportReadErrorScenarios | 8 | 0 | 1.399 |
| LifecycleRaceScenarios | 24 | 0 | 3.081 |
| OrderlyShutdownScenarios | 32 | 0 | 1.651 |
| ParallelTimingScenarios | 8 | 0 | 4.738 |
| RequestImportScenarios | 40 | 0 | 1.837 |
| SimulationControllerScenarios | 14 | 0 | 1.627 |
| StopContinuationScenarios | 21 | 0 | 5.469 |
| FileSelectionScenarios | 12 | 0 | 1.332 |

Los 533 escenarios son 501 previos y 32 nuevos. No equivalen a la suite JUnit completa ni cuentan cada iteración interna como prueba nueva. Los tiempos observados no son garantías.

## Evidencia nueva

| Grupo | Casos | Cobertura |
| --- | ---: | --- |
| ReadFailureScenarios | 12 | Siete archivos válidos, quince inválidos, bytes especiales, repetidos, denegación y SecurityException, lectura/cierre/suprimidas, límite, recuperación y lecturas concurrentes. |
| FileSelectionScenarios | 12 | Cancelación, aprobación, errores corregibles, vacío, estado, EDT, fallo/null de selector, cambio durante diálogo, reentrada modal y respuesta obsoleta. |
| ImportReadErrorScenarios | 8 | Conservación del lote frente al catálogo inválido, acceso denegado con lector real, lectura parcial, cancelación sin interferir, reset, desaparición, hilos y servicio real de los siete válidos. |

Las pruebas de selección ejecutan el manejador en el EDT real, pero inyectan las decisiones de selección. No crean un JFileChooser nativo. Los callbacks se comprueban en EDT. La entrega diferida se retiene por un despachador de prueba; no se bloquea el EDT para crear la carrera.

La integración espera eventos de servicio y estados SERVED del manejador, siguiendo UUID canónicos/alias para repetidos. No confunde ENQUEUED con atención. Los latches usados para desaparición del archivo y lectura activa se liberan en finally; las sesiones esperan cierre real y los ejecutores de prueba se terminan.

## Recursos y empaquetado

Los 22 .txt se cargaron por classloader y se copiaron temporalmente para importar por Path. Un JAR auxiliar se creó con las clases compiladas y recursos y se ejecutó desde /tmp; ReadFailureScenarios terminó con salida 0 y sus 12 escenarios. Esto comprueba independencia del directorio de trabajo y conservación de recursos, pero no sustituye la construcción Maven pendiente.

Se verificaron BOM EF BB BF, CRLF, tab real, CR aislado, UTF-8 malformado y vacío de cero bytes. Las reglas .gitattributes preservan los datos y evitan diff textual para los tres casos especiales indicados en el catálogo. .editorconfig excluye estos recursos de las reglas de normalización del código. Estas actualizaciones de configuración y documentación no cambian los bytes de los 22 datos ya probados.

## Permisos: evidencia y límite

Se inyectó AccessDeniedException en InputOpener, el punto que usa read(Path) para abrir su recurso, y se comprobaron código READ_ERROR, causa original, conservación del lote, reintento y servicio posterior con el mismo controlador. No se omite la lógica de RequestFileReader ni se sustituye su resultado por uno fabricado en esas pruebas. Se probaron también SecurityException y fallos de lectura/cierre del stream propio.

No se comprobó una ACL real de Windows ni una denegación efectiva de permisos POSIX. El proceso local tiene UID 0; se intentó una comprobación adicional sobre un archivo temporal con un proceso sin privilegios, pero el entorno impidió establecer grupos (Operation not permitted). Los permisos del proyecto no se modificaron; los archivos temporales se eliminaron. No se presenta ese intento como una prueba satisfactoria.

## Pendiente en Windows

```powershell
mvn "-Dtest=ReadFailureTest,FileSelectionTest,ImportReadErrorTest" test
mvn "-Dtest=ReadFailureTest,FileSelectionTest,ImportReadErrorTest,RequestFileTest,RequestImportTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.FileSelectionDemo
```

Se esperan 32 específicas, 120 seleccionadas y 578 acumuladas, sin fallos, errores u omisiones y con BUILD SUCCESS. La prueba visual debe confirmar cargar, cancelar sin borrar, corregir errores, cargar otro lote y cerrar con retorno al prompt, según la [ficha T46](../implementacion/T46-ejemplos-y-errores-lectura.md). No se ejecutó una ventana JFrame ni un diálogo nativo aquí.

T46 y la fase 7 permanecen pendientes de esa confirmación e incorporación. Commit sugerido: `T46: crear ejemplos y probar errores de lectura`. Después corresponde T47 en fase 8.

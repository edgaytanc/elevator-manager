# T53 — Recorrido de aceptación desde GUI

**Estado inicial del acta:** pendiente de ejecución nativa por David. No se adjuntan capturas simuladas como evidencia del escritorio.

## Preparación

Extraer el ZIP en la raíz Maven conservando .git. Construir y abrir una sola vez:

```powershell
mvn "-Dtest=GuiJourneyTest" test
mvn "-Dtest=GuiJourneyTest,EdtSafetyTest,EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Resultados esperados: 10 específicas, 186 del grupo de interfaz, 764 totales, cero fallos/errores. Una vez abierta la ventana, realizar lo siguiente exclusivamente mediante la GUI. No se requieren comandos adicionales mientras se usa el simulador.

## Recorrido principal

| Paso | Acción gráfica | Comprobación y evidencia |
| --- | --- | --- |
| 1 | Introducir N=2, P=8, movimiento=1000 ms, espera=300 ms; Validar configuración | RESET, lote 0, Start/Cargar disponibles; campos bloqueados. Captura A |
| 2 | Pulsar Start | RUNNING; llamadas y destinos habilitados; Cargar archivo deshabilitado |
| 3 | En llamadas externas elegir piso 3 y Subir | La llamada se asigna a una cabina; su lista muestra piso y sentido mientras esté pendiente. El mensaje de envío no equivale a servicio |
| 4 | Inspeccionar la cabina asignada con Cabina/Ver cabina o su pestaña | Ver avance de piso/siguiente, dirección y evento de parada en 3. La recogida desaparece tras atención |
| 5 | Elegir cabina 2 y destino 8 | Destino 8 amarillo con ● tras admisión, presente en pendientes. Captura B antes de llegar |
| 6 | Pulsar Stop mientras el destino 8 sigue pendiente | STOPPING y después STOPPED; termina el tramo comprometido. Destino conservado y botones bloqueados; no se congela a mitad del tramo |
| 7 | Pulsar Start | Continúa la misma sesión y atiende 8; luz y pendiente desaparecen al atenderlo. No se crean trabajadores nuevos |
| 8 | Pulsar Reset y esperar su finalización | RESETTING durante retorno; finalmente RESET, ambas cabinas en 1/Arriba, sin luces ni recogidas. Captura C |
| 9 | Cargar archivo; seleccionar src/main/resources/examples/requests/23-recorrido-gui.txt del proyecto extraído | RESET y Lote preparado: 4; sin movimiento antes de Start. Captura D |
| 10 | Pulsar Start | Ejecución del lote: destinos 8/6 y llamadas 3/Subir, 7/Bajar; observar atención en visor y limpieza de pendientes |
| 11 | Tras completar el lote, Stop y Start | No se vuelve a ejecutar el lote consumido ni reaparecen sus destinos |
| 12 | Desmarcar Seguir últimos, inspeccionar eventos, pulsar Limpiar visor | Se limpia la vista; archivos de sesión se conservan; nuevas acciones producen eventos nuevos |
| 13 | Cambiar tamaño de ventana y mover divisores; cambiar cabinas | Controles accesibles, desplazamiento disponible y actualización sin bloqueo perceptible. Captura E |
| 14 | Reset y esperar ambas cabinas en 1/Arriba; después Cerrar | Ventana desaparece y vuelve el prompt; logs con ambos RESET_COMPLETED, ambos WORKER_TERMINATED y MANAGER_TERMINATED |

Si el destino del paso 6 ya se atendió, repetir el paso 5 hacia el extremo opuesto y pulsar Stop antes de llegar. El visor muestra una copia reciente acotada: para auditar toda la sesión usar los archivos guardados en logs/sesion-UUID. La indicación de envío de una llamada puede permanecer como resultado de la última acción aunque la atención ya conste en visor y agendas.

## Variantes de aceptación

- [ ] En RESET, cancelar el diálogo conserva el lote preparado.
- [ ] Cargar `22-sentido-extremo.txt` con N=2/P=8 muestra error y conserva un lote válido anterior; no admite un prefijo parcial.
- [ ] En piso 1 Bajar está bloqueado; en piso 8 Subir está bloqueado.
- [ ] En otra ejecución, N=1/P=1 permite destino interno 1 y bloquea ambos sentidos externos.
- [ ] En otra ejecución, N=4/P=25 permite Ver cabina 4, Siguiente y destino 25; cambiar pestaña y regresar conserva el pendiente mientras no se haya atendido.
- [ ] Cerrar también con X durante un movimiento devuelve el prompt sin errores de cierre.
- [ ] No hay errores de sesión, excepciones Swing ni bloqueo al accionar controles durante movimiento/carga.

La ejecución headless cubre componentes y dominio, pero estas variantes confirman diálogo, ratón, teclado, foco, repintado, redimensionado y cierre de ventana nativos.

## Acta a completar

- Fecha y Java/Maven: pendiente.
- Commit base: 6eec38e.
- Pruebas específicas / interfaz / total: pendiente.
- Pasos 1–14 y variantes: pendiente.
- Capturas A–E y directorio de logs: pendiente.
- Fluidez observada y resultado del botón Cerrar / X: pendiente.
- Incidencias reproducibles: pendiente.
- Commit T53: pendiente.

La fase 8 se dará por cerrada al confirmar el recorrido y los resultados. No marcar esta acta como aprobada solo por tener BUILD SUCCESS.

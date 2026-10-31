# T57 — Aceptación de pausa y cierre de fase 9

- **Fecha:** 31 de octubre de 2026, Guatemala.
- **Estado:** pendiente de completar con resultados de David.
- **Base disponible:** T56 incorporada en `7dfb8e5`, 28/838 pruebas Maven correctas. El registro nativo anterior acredita tres ciclos de pausa/reanudación y cierre normal; no acredita todos los controles durante pausa ni la estabilidad visual de capturas.

## Automatización nueva

Desde la raíz del proyecto, después de incorporar el ZIP conservando .git:

```powershell
mvn "-Dtest=PausePhaseTest" test
mvn clean verify
git diff --check
```

Esperado: 18/0/0/0 específicas, 856/0/0/0 totales y BUILD SUCCESS. Los tests emiten T57_TIMING con nombre de caso, restante, tiempo activo tras reanudación, tiempo de pared tras reanudación y tolerancia; todos los números están en nanosegundos. Conservar la salida si aparece un fallo temporal, sin ocultarlo mediante repeticiones hasta obtener verde.

## Revisión gráfica pendiente

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

1. Configurar dos cabinas y seis pisos con tiempos suficientemente visibles, por ejemplo 1500 ms entre pisos y 2500 ms de parada. Iniciar, marcar destino y pausar durante movimiento. Esperar varios segundos: piso/siguiente, sentido, fase En pausa y luz deben permanecer. Reanudar y comprobar continuación/apagado al atender.
2. Pausar durante una parada y luego sin trabajo. En ambos casos conservar piso y sentido; en reposo no crear solicitudes ni reiniciar en piso 1. Repetir varios ciclos. La medida de 250 ms se verifica por automatización, no cronometrando clics a mano.
3. Pausar entre pisos y pulsar Stop: llegar al adyacente sin servir todavía el destino; continuar con Start. Pausar de nuevo y pulsar Reset: retorno físico a 1/UP y limpieza sin Resume manual.
4. En ejecuciones separadas, cerrar desde PAUSED con Cerrar y con X. Confirmar desaparición de ventana, terminación de actores y retorno al prompt. Estos cierres no deben requerir Reanudar.
5. Reducir el ancho y comprobar acceso a los siete controles por la barra horizontal. Inspeccionar SIMULATION_PAUSING/PAUSED/RESUMED y PAUSE_SUPERSEDED en visor/archivo. El [guion T56](T56-recorrido-gui.md) conserva el recorrido detallado.

La interfaz actual muestra la posición mediante piso/siguiente y progreso capturado. La animación gráfica del edificio corresponde a la fase 10 y no se exige aquí.

## Acta de cierre

| Evidencia | Estado |
| --- | --- |
| T54, reloj: 17/781 pruebas; commit 4e34f89 | Confirmada |
| T55, barrera: 29/810 pruebas; commit dd9dc82 | Confirmada |
| T56, controles: 28/838 pruebas; commit 7dfb8e5 | Confirmada |
| T56, tres ciclos Pausar/Reanudar y cierre normal en consola | Confirmada |
| T57, 18 pruebas Maven específicas | Pendiente |
| T57, suite Maven completa de 856 | Pendiente |
| Capturas nativas estables en movimiento/parada/reposo | Pendiente de confirmación |
| Stop/Reset durante pausa en escritorio | Pendiente de confirmación |
| Cerrar y X durante pausa en escritorio | Pendiente de confirmación |
| Controles con ventana estrecha y visor/archivo | Pendiente de confirmación |
| Revisión Git y commit T57 con árbol limpio | Pendiente |

Una vez confirmadas las evidencias, actualizar esta acta y el plan, marcar T56/T57 completadas y cerrar formalmente la fase 9. Mantener la fase 10 pendiente hasta entonces; su primera tarea es T58.

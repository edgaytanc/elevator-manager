# T56 — Recorrido nativo de pausa y prioridades

- **Estado:** ejecución parcial documentada por David; Maven satisfactorio y tres ciclos Pausar/Reanudar con cierre normal. Se conservan pendientes las comprobaciones no acreditadas en el registro.
- **Preparación:** incorporar el ZIP, ejecutar Maven y arrancar el JAR desde la raíz.

```powershell
mvn "-Dtest=PausePriorityTest,PauseControlsTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

## Recorrido

1. Configurar dos elevadores, seis pisos, 700 ms entre pisos y 1000 ms de parada. Validar: RESET, Start/carga disponibles; Pausar/Reanudar bloqueados.
2. Pulsar Start y seleccionar destino 6 en cabina 1. Comprobar piso actual/siguiente, luz 6 y eventos de salida.
3. Pulsar Pausar mientras está entre pisos. Puede verse PAUSING antes de PAUSED; nunca interpretar el primer clic como confirmación de todos los hilos. En PAUSED deben seguir disponibles Reanudar, Stop, Reset y Cerrar.
4. Esperar varios segundos: piso/siguiente, luz y fase En pausa deben conservarse. Las llamadas y destinos no deben admitir pulsaciones durante pausa. La animación gráfica del edificio se implementará en la fase 10; aquí se inspeccionan las capturas de cabina.
5. Pulsar Reanudar. Debe continuar el mismo recorrido y apagar la luz 6 al atenderlo, sin volver a comenzar desde piso 1. Revisar SIMULATION_PAUSING, SIMULATION_PAUSED y SIMULATION_RESUMED en el visor.
6. Seleccionar otro destino y pausar durante una parada de servicio. Reanudar y comprobar que consume el resto de la parada, no otra parada completa. La comprobación temporal precisa está automatizada; una observación visual solo la complementa.
7. Reset y Start para preparar una prueba limpia. Marcar destino 2 desde piso 1, pausar durante el tramo 1 a 2 y pulsar Stop. Debe llegar a 2 sin atender todavía el destino: conservar luz 2 y mostrar STOPPED. Start debe atenderlo y apagarlo.
8. Marcar destino 6, pausar durante movimiento y pulsar Reset. Debe retornar físicamente a 1/UP, limpiar luces/llamadas y terminar en RESET. No pulsar Reanudar para conseguirlo. La carga vuelve a estar disponible.
9. Desde RUNNING, pulsar Pausar y enseguida Stop; repetir con Pausar y enseguida Reset. El último control prioritario debe terminar sin reaparecer después PAUSED por una respuesta tardía. La automatización realiza estas pulsaciones en el mismo turno EDT.
10. Iniciar otra ejecución, pausar y cerrar con Cerrar. La ventana debe desaparecer y PowerShell recuperar el prompt. Repetir en otra ejecución cerrando con X mientras está pausada.
11. Reducir el ancho de la ventana. Comprobar que los siete controles siguen accesibles mediante la barra horizontal, sin una segunda fila recortada.
12. Revisar los archivos de la última sesión después de cerrar y conservar capturas/logs si se desean como evidencia.

```powershell
$sesionT56 = Get-ChildItem .\logs -Directory -Filter 'sesion-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Select-String -Path (Join-Path $sesionT56.FullName 'manejador.log') -Pattern 'SIMULATION_|PAUSE_SUPERSEDED'
git diff --check
```

## Registro de resultados

- Maven específico: confirmado 28/0/0/0 y BUILD SUCCESS.
- Maven completo: confirmado 838/0/0/0 y BUILD SUCCESS.
- PAUSED/Reanudar: tres ciclos confirmados en consola; estabilidad visual de capturas pendiente de confirmación.
- Stop durante pausa: pendiente.
- Reset durante pausa: pendiente.
- Cerrar y X durante pausa: pendiente.
- Acceso a controles al reducir ancho: pendiente.
- Transiciones: confirmadas en consola; inspección nativa del visor/archivo pendiente de confirmación.

Tras confirmar, registrar resultados y marcar T56 completada en el plan. No cerrar aún la fase 9: T57 amplía las pruebas de pausa.

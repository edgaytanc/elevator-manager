# T66 — Recorrido gráfico tras integrar el protocolo

- **Estado:** pendiente de revisión nativa por David.
- **Propósito:** confirmar que el protocolo integrado conserva los extras visuales ya aceptados en T61 y los controles de T65.

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

| Paso | Acción | Resultado esperado |
| --- | --- | --- |
| 1 | Configurar 2 cabinas, 6 pisos, movimiento 1000 ms y espera 1500 ms; Start. | Ventana responde y ambas cabinas en piso 1. |
| 2 | Enviar cabina 1 al piso 6 y cabina 2 al piso 3. | Se mueven independientemente y se muestran los destinos pendientes. |
| 3 | Pausar con una cabina en movimiento; esperar unos segundos y desplazar/redimensionar. | Posición congelada y repintado correcto; interfaz responde. |
| 4 | Reanudar, Stop y Start. | Continúa desde el punto conservado; Stop termina el tramo adyacente; Start conserva el trabajo pendiente. |
| 5 | Reset durante movimiento y esperar su confirmación. | Retorno animado de cada cabina al piso 1, dirección UP y luces/listas vacías. |
| 6 | Start; crear una llamada externa y un destino interno; cerrar durante actividad. | Se atienden solicitudes; visor/consola/archivos de logging siguen activos; cierre devuelve el prompt. |

La interfaz no muestra las cadenas V1 como parte del flujo del usuario. La traza del protocolo se revisa con ProtocolAcceptanceDemo. No atribuir a una captura estática pruebas de fluidez o de cierre; registrar también el resultado observado.

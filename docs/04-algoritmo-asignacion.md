# 04 — Algoritmo de asignación y recorrido

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-19, Guatemala.
- **Estado:** especificación propuesta, sin código ni mediciones realizadas.
- **Base:** [decisiones D17–D24](02-decisiones.md) y [arquitectura](03-arquitectura.md).

## 1. Problema y objetivo

Dada una llamada externa `(piso, dirección)`, elegir una cabina que pueda recogerla pronto sin violar su recorrido comprometido. Minimizar primero la espera estimada hasta recogida; usar carga y distancia estimada como desempates. El enunciado pide eficiencia, pero no define una función matemática ni exige demostrar óptimo global. Esta propuesta hace la decisión determinista y comprobable.

La solución consta de dos piezas: un planificador local que ordena paradas por sentido y una estrategia global que proyecta ese plan para cada cabina. Ambas comparten reglas; no se permite estimar una ruta y ejecutar otra distinta de manera sistemática.

## 2. Datos de entrada y salida

| Entrada | Uso |
| --- | --- |
| HallRequest | Piso de recogida y dirección de servicio solicitada. |
| Configuración | Pisos y duraciones de tramo/parada comunes a todas las cabinas. |
| Snapshots | Piso, fase, siguiente piso, restante, sentido, frontera, agenda, época, revisión y disponibilidad. |
| Solicitudes ya enviadas sin ACK | Incluirlas en la carga prevista para no asignar como si la cabina siguiera vacía. No duplicarlas si el snapshot ya refleja su comando. |

Salida: ID elegido, ETA en ms, paradas/tramos previos estimados, carga, revisión/época utilizadas y explicación resumida. Si no hay cabina disponible, mantener la llamada sin asignar y mostrar incidencia/espera; no marcarla completada.

## 3. Planificador local por barridos

### 3.1 Definiciones

- **Tramo:** movimiento entre pisos adyacentes; durante él no se cambia de sentido.
- **Parada de servicio:** detención con duración configurada, que empieza al recoger/dejar solicitudes compatibles en un piso.
- **Barrido:** avance monótono UP o DOWN con frontera comprometida.
- **Frontera:** piso más lejano comprometido del barrido en curso. No se reduce por una llamada nueva detrás de la cabina.
- **Dirección de viaje:** sentido físico del siguiente tramo.
- **Dirección de servicio:** sentido solicitado por una llamada externa; puede requerir invertir al llegar a un punto de recogida.

El compromiso incluye el destino que ya se ejecutaba antes de recibir una llamada nueva, no solo el siguiente piso adyacente. No basta llegar al siguiente piso y dar vuelta antes del destino previamente comprometido.

### 3.2 Construcción y actualización

1. Si no existe barrido activo, elegir el objetivo pendiente más próximo por tramos; desempatar por orden de admisión y luego piso. Crear barrido hasta ese objetivo. Estar IDLE/UP inicialmente no obliga a subir si el primer servicio exige otra orientación.
2. Un destino interno admitido por delante, en el sentido del barrido, se incorpora y puede ampliar la frontera. No contraer la frontera existente.
3. Una llamada externa por delante y con sentido compatible se incorpora al barrido si está antes de la frontera o en ella. Una externa más allá de la frontera se difiere para impedir que un flujo de llamadas externas extienda indefinidamente un mismo barrido.
4. Solicitudes detrás, externas incompatibles y destinos al piso recién dejado se conservan diferidos. Nunca se descartan por estar fuera del barrido actual.
5. Al alcanzar la frontera, completar servicios compatibles antes de decidir el siguiente barrido. Si hay trabajo detrás, invertir. Si no lo hay, continuar hacia trabajo por delante o quedar IDLE. Conservar la dirección de último servicio/viaje en reposo.
6. Si existen llamadas contrarias en la frontera y va a comenzar un barrido en ese sentido, cambiar dirección de servicio y atenderlas allí antes de salir. No invertir en una parada intermedia si todavía queda compromiso por delante.
7. Si quedan solicitudes en el mismo piso al concluir el barrido, procesarlas antes de salir según su sentido permitido. Una llamada externa puede constituir por sí sola el objetivo del siguiente barrido.

**Excepción aparente de cabina libre:** para atender una llamada DOWN por encima de su posición, viaja UP hasta el origen, cambia a DOWN allí y recoge. La dirección de servicio se decide al recoger, no al seleccionar cabina. Si esa llamada está en un piso intermedio de otro compromiso UP, debe esperar un barrido compatible.

### 3.3 Servicio y duplicados

Una parada puede satisfacer varias solicitudes internas del piso y externas compatibles. Si se agregan solicitudes compatibles mientras la misma parada sigue abierta, se pueden completar en ese instante sin reiniciar su duración. Su luz queda encendida hasta la aceptación de servicio, no hasta el ACK de admisión.

La finalización de la solicitud se define al **inicio de su servicio**: ya se ha recogido/dejado al usuario simulado; el tiempo de parada debe terminar antes de otro movimiento. El registro del fin de la parada es distinto del evento que completa la solicitud. En reset, las solicitudes ya completadas no se convierten en canceladas retroactivamente.

Si una parada UP termina y hay que servir una externa DOWN en el mismo piso para regresar, registrar cambio de sentido y abrir otra parada de servicio con duración completa. Esta regla evita considerar atendidas dos direcciones sin hacer explícita la transición y simplifica la ETA.

Duplicados funcionales activos se agrupan conservando la identidad canónica. No duplicar una parada por dos comandos equivalentes. El protocolo sigue respondiendo al ID de transporte de cada mensaje.

## 4. Estimación de recogida

Para cada cabina disponible, clonar el snapshot y la agenda; incorporar la nueva llamada siguiendo las reglas anteriores. Proyectar eventos del planificador sin esperas reales y sin introducir solicitudes futuras.

La ETA se mide desde la decisión hasta el inicio de servicio de la llamada candidata:

`ETA = restante físico inicial + suma de tramos posteriores + suma de paradas anteriores a su recogida`.

- En MOVING usar el restante del tramo actual y luego movimientos completos; no contar ese mismo tramo dos veces.
- En DWELLING usar el restante de la parada, salvo que la candidata pueda servirse en esa parada compatible aún abierta: entonces ETA = 0.
- IDLE en el piso y con servicio posible: ETA = 0; se puede cambiar a la dirección solicitada sin tiempo mecánico adicional, pues el enunciado no fija tiempo de inversión.
- No sumar a ETA la duración de la parada de recogida de la propia candidata. Sí influye en futuras solicitudes asignadas después.
- Excluir cabinas fallidas, terminadas, en retorno por reset o de otra época. No asignar nuevas llamadas fuera de RUNNING.
- Si no puede proyectarse de forma consistente, señalar error del planificador; no devolver costo 0 ni escoger cabina arbitrariamente.

El proyectador debe avanzar entre eventos, no iterar por cada milisegundo. La terminación se comprueba sobre una lista finita de solicitudes; una detección de ciclo sin progreso indica defecto del algoritmo.

## 5. Selección y compromiso de asignación

Comparar candidatos mediante esta tupla lexicográfica:

1. Menor ETA de recogida.
2. Menor número de solicitudes pendientes distintas, incluyendo las enviadas aún sin confirmar.
3. Menor número de tramos proyectados antes de recoger.
4. Menor ID de cabina.

Antes de enviar: verificar RUNNING y época vigentes. La ejecución de la cabina puede avanzar desde el snapshot; su trabajador incorpora el comando respecto a su estado actual, sin infringir las reglas. La estimación es provisional. Si la cabina rechaza antes de admitir, refrescar y recalcular; si acepta, mantener la asignación. Si cambia la época, cancelar el intento, sin reenviarlo en la nueva generación.

La emisión de varias asignaciones sucesivas considera los comandos sin ACK como agenda provisional. Tras ACK/snapshot correlacionado retirar la proyección provisional sin perder ni contar dos veces la solicitud.

## 6. Ejemplos calculados de referencia

Valores teóricos: movimiento M = 1000 ms; parada S = 2000 ms. No representan resultados ejecutados.

| Caso | Estado inicial y llamada | Cálculo | Resultado esperado |
| --- | --- | --- | --- |
| E01 | A libre en 1/UP; llamada 3/UP. | Dos tramos, ninguna parada previa. | ETA A = 2000 ms. |
| E02 | A en 2/UP comprometida a 8 por destino interno; B libre en 5; llamada 3/DOWN. | A: 2→8 = 6000, parada 8 = 2000, 8→3 = 5000. B: 5→3 = 2000. | ETA A = 13000; B = 2000. Elegir B. |
| E03 | A en 2/UP, frontera 8, interna 8; llamada 5/UP. | 2→5 = 3000, sin parada previa. | Recoger en 5, esperar S y continuar a 8; no esperar al retorno. |
| E04 | A en tramo 2→3, restante 400 ms, sin otra parada antes de 4; llamada 4/UP y frontera ≥ 4. | 400 + 1000. | ETA = 1400 ms, no 2000 ni 2400. |
| E05 | A en parada compatible en 3/UP, restante 700 ms; llamada 3/UP. | Recogida en parada abierta. | ETA = 0, restante sigue en 700; no se reinicia. |
| E06 | A en 2→3 con restante 400 ms y destino interno 3; nueva llamada 2/DOWN. | 400 + parada 3 de 2000 + tramo 3→2 de 1000. | ETA = 3400 ms; no recoger inmediatamente en 2. |
| E07 | A libre en 1/UP; única llamada 3/DOWN. | 1→3 = 2000; cambio de servicio a DOWN en 3. | Recoge en 3/DOWN y registra inversión. |
| E08 | A y B libres en 1, misma carga; llamada 3/UP. | ETA y tramos iguales. | Elegir menor ID. |
| E09 | A subiendo de 2 a frontera 8; externas 5/UP y 5/DOWN. | Servicio UP intermedio; DOWN diferido. | La recogida UP no completa la DOWN. |

Para E02 se asume que solo existen las solicitudes descritas y que ninguna nueva se incorpora mientras se calcula. Las referencias de piso en ejemplos inmóviles son puntos exactos entre tramos, no posiciones a mitad de viaje.

## 7. Métricas y límites

Medir por solicitud externa: admisión, aceptación de asignación e inicio de recogida, descontando pausa/Stop si se presenta «espera simulada». Conservar también tiempo de pared para evitar confusiones. Medir por cabina: tramos, paradas e inversiones. Comparar escenarios con la misma carga y configuración.

La política no garantiza ETA exacta bajo entradas nuevas ni optimalidad global. La restricción de frontera para llamadas externas reduce extensión indefinida de barridos, pero no demuestra ausencia de inanición frente a cualquier flujo infinito. La condición de aceptación es que lotes finitos terminen, salvo cancelación explícita o incidencia reportada.

No reclamar una complejidad numérica sin elegir estructuras y medir implementación. Se recorrerán N candidatos y se proyectará su agenda; registrar costo real si se detectan problemas con tamaños grandes.

## 8. Verificación e historial

T25 y T30–T34 implementarán estas reglas; T68 comprobará E01..E09 y casos adicionales. Actualizar ejemplos si cambia una decisión del planificador, antes de aceptar pruebas con nuevos resultados.

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Reglas de barrido, frontera, servicio, ETA, desempates y nueve ejemplos de referencia. |

**Siguiente lectura:** [05 — Protocolo](05-protocolo.md).

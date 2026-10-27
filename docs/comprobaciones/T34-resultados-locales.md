# T34 — Resultados de eficiencia y escalabilidad

- **Ejecución UTC:** 2026-10-27T18:17:49.214457612Z.
- **Java:** 17.0.20; **SO:** Linux.
- **Cabinas:** [1, 2, 4]; **repeticiones:** 3; **pisos:** 8; **movimiento:** 25 ms; **parada:** 10 ms.

Se conserva la misma lista y orden de llamadas por carga. Todas las cabinas parten del piso 1, UP y sin agenda; cada ejecución usa actores nuevos. El orden de N rota en cada repetición; con tres repeticiones cada configuración ocupa cada posición una vez. La espera va desde submit hasta el timestamp de COMMAND_COMPLETED compatible. El tiempo total incluye el fin de la última parada, pero excluye crear/cerrar hilos.

## Resumen comparativo

Los percentiles se calculan sobre todas las llamadas de las repeticiones del grupo; los movimientos y paradas son promedios por ejecución. La mejora de espera compara la media con N=1 de la misma carga; un valor negativo significa más espera.

| Carga | N | Muestras | Espera media ms | p95 ms | Máxima ms | Movimientos medios | Paradas medias | Tiempo total medio ms | Cabinas con servicio medias | Mejora espera % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| subida | 1 | 18 | 122.116 | 227.356 | 227.356 | 6.000 | 6.000 | 220.455 | 1.000 | 0.00 |
| subida | 2 | 18 | 99.134 | 173.974 | 173.974 | 11.000 | 6.000 | 182.622 | 2.000 | 18.82 |
| subida | 4 | 18 | 92.871 | 162.818 | 162.818 | 18.000 | 6.000 | 172.597 | 4.000 | 23.95 |
| mixta | 1 | 18 | 139.031 | 252.833 | 252.833 | 8.000 | 6.000 | 262.622 | 1.000 | 0.00 |
| mixta | 2 | 18 | 104.795 | 231.951 | 231.951 | 11.667 | 6.000 | 201.759 | 2.000 | 24.63 |
| mixta | 4 | 18 | 92.206 | 161.611 | 161.611 | 17.000 | 6.000 | 171.702 | 4.000 | 33.68 |
| opuestos | 1 | 18 | 156.328 | 355.055 | 355.055 | 12.000 | 6.000 | 364.288 | 1.000 | 0.00 |
| opuestos | 2 | 18 | 109.885 | 231.969 | 231.969 | 11.333 | 6.000 | 203.831 | 2.000 | 29.71 |
| opuestos | 4 | 18 | 83.781 | 162.105 | 162.105 | 16.000 | 6.000 | 171.944 | 4.000 | 46.41 |

## Decisiones y límites

- La política vigente elige mínimo ETA y desempata por ID ascendente; una cabina con admisión pendiente queda reservada hasta confirmar su agenda.
- Los dos sentidos en un piso son pedidos distintos. Estas cargas excluyen duplicados funcionales para no confundir alias con atenciones independientes.
- Más cabinas permiten recorridos simultáneos, pero pueden aumentar el desplazamiento agregado y concentrar empates en IDs menores. No se exige mejora monótona ni reparto uniforme.
- Es una evaluación de cargas pequeñas y tiempos escalados, sin calentamiento JIT controlado, carga sostenida ni prueba estadística de significancia. No mide capacidad máxima ni demuestra optimalidad global.
- Los tiempos incluyen despacho, planificación del SO y reintentos. Los movimientos son tramos adyacentes confirmados por ARRIVED, sumados sobre todas las cabinas.

## Resultados por ejecución

| Carga | N | Repetición | Llamadas | Media ms | p95 ms | Movimientos | Paradas | Tiempo total ms | Servicios por cabina | Movimientos por cabina |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| subida | 1 | 1 | 6 | 139.086 | 227.356 | 6 | 6 | 237.617 | 1:6 | 1:6 |
| subida | 2 | 1 | 6 | 100.081 | 173.974 | 11 | 6 | 184.144 | 1:3; 2:3 | 1:5; 2:6 |
| subida | 4 | 1 | 6 | 93.390 | 162.818 | 18 | 6 | 172.950 | 1:2; 2:2; 3:1; 4:1 | 1:5; 2:6; 3:3; 4:4 |
| subida | 2 | 2 | 6 | 98.903 | 172.138 | 11 | 6 | 182.256 | 1:3; 2:3 | 1:5; 2:6 |
| subida | 4 | 2 | 6 | 92.932 | 162.714 | 18 | 6 | 172.836 | 1:2; 2:2; 3:1; 4:1 | 1:5; 2:6; 3:3; 4:4 |
| subida | 1 | 2 | 6 | 113.605 | 201.689 | 6 | 6 | 211.858 | 1:6 | 1:6 |
| subida | 4 | 3 | 6 | 92.290 | 161.843 | 18 | 6 | 172.005 | 1:2; 2:2; 3:1; 4:1 | 1:5; 2:6; 3:3; 4:4 |
| subida | 1 | 3 | 6 | 113.657 | 201.765 | 6 | 6 | 211.890 | 1:6 | 1:6 |
| subida | 2 | 3 | 6 | 98.419 | 171.362 | 11 | 6 | 181.467 | 1:3; 2:3 | 1:5; 2:6 |
| mixta | 1 | 1 | 6 | 139.008 | 252.633 | 8 | 6 | 262.803 | 1:6 | 1:8 |
| mixta | 2 | 1 | 6 | 98.707 | 171.464 | 11 | 6 | 181.558 | 1:3; 2:3 | 1:6; 2:5 |
| mixta | 4 | 1 | 6 | 92.277 | 161.611 | 17 | 6 | 171.739 | 1:2; 2:2; 3:1; 4:1 | 1:6; 2:5; 3:4; 4:2 |
| mixta | 2 | 2 | 6 | 98.657 | 171.548 | 11 | 6 | 181.645 | 1:3; 2:3 | 1:6; 2:5 |
| mixta | 4 | 2 | 6 | 92.359 | 161.565 | 17 | 6 | 171.706 | 1:2; 2:2; 3:1; 4:1 | 1:6; 2:5; 3:4; 4:2 |
| mixta | 1 | 2 | 6 | 139.441 | 252.833 | 8 | 6 | 262.973 | 1:6 | 1:8 |
| mixta | 4 | 3 | 6 | 91.984 | 161.567 | 17 | 6 | 171.661 | 1:2; 2:2; 3:1; 4:1 | 1:6; 2:5; 3:4; 4:2 |
| mixta | 1 | 3 | 6 | 138.644 | 251.953 | 8 | 6 | 262.090 | 1:6 | 1:8 |
| mixta | 2 | 3 | 6 | 117.020 | 231.951 | 13 | 6 | 242.075 | 1:4; 2:2 | 1:8; 2:5 |
| opuestos | 1 | 1 | 6 | 155.624 | 353.029 | 12 | 6 | 363.194 | 1:6 | 1:12 |
| opuestos | 2 | 1 | 6 | 91.962 | 162.229 | 10 | 6 | 172.446 | 1:4; 2:2 | 1:4; 2:6 |
| opuestos | 4 | 1 | 6 | 83.950 | 162.105 | 16 | 6 | 172.303 | 1:2; 2:2; 3:1; 4:1 | 1:6; 2:2; 3:4; 4:4 |
| opuestos | 2 | 2 | 6 | 117.039 | 231.969 | 12 | 6 | 242.098 | 1:4; 2:2 | 1:8; 2:4 |
| opuestos | 4 | 2 | 6 | 83.847 | 161.540 | 16 | 6 | 171.729 | 1:2; 2:1; 3:2; 4:1 | 1:6; 2:2; 3:4; 4:4 |
| opuestos | 1 | 2 | 6 | 157.118 | 355.055 | 12 | 6 | 365.266 | 1:6 | 1:12 |
| opuestos | 4 | 3 | 6 | 83.545 | 161.648 | 16 | 6 | 171.799 | 1:2; 2:2; 3:1; 4:1 | 1:6; 2:2; 3:4; 4:4 |
| opuestos | 1 | 3 | 6 | 156.241 | 354.217 | 12 | 6 | 364.405 | 1:6 | 1:12 |
| opuestos | 2 | 3 | 6 | 120.656 | 186.761 | 12 | 6 | 196.949 | 1:4; 2:2 | 1:6; 2:6 |

## Decisiones iniciales del manejador

Se conserva el resultado real de cada entrada. Si fue PENDING, la asignación posterior se consulta en las observaciones; no se inventa un ETA inicial.

| Carga | N | Repetición | Índice | Resultado y decisión inicial |
| --- | ---: | ---: | ---: | --- |
| subida | 1 | 1 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 1 | 1 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 1 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 2 | 1 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 2 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 1 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 4 | 1 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 4 | 1 | 3 | DISPATCHED: Enviado por menor ETA=75000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| subida | 4 | 1 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| subida | 4 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 1 | 6 | DISPATCHED: Enviado por menor ETA=157516736 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=6 |
| subida | 2 | 2 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 2 | 2 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 2 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 2 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 4 | 2 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 4 | 2 | 3 | DISPATCHED: Enviado por menor ETA=75000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| subida | 4 | 2 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| subida | 4 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 2 | 6 | DISPATCHED: Enviado por menor ETA=158628969 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=6 |
| subida | 1 | 2 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 1 | 2 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 3 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 4 | 3 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 4 | 3 | 3 | DISPATCHED: Enviado por menor ETA=75000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| subida | 4 | 3 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| subida | 4 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 4 | 3 | 6 | DISPATCHED: Enviado por menor ETA=158535674 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=6 |
| subida | 1 | 3 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 1 | 3 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 1 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 3 | 1 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| subida | 2 | 3 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| subida | 2 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| subida | 2 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 1 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 1 | 1 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 1 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 2 | 1 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 2 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 1 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 4 | 1 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 4 | 1 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| mixta | 4 | 1 | 4 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| mixta | 4 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 1 | 6 | DISPATCHED: Enviado por menor ETA=73507366 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=6 |
| mixta | 2 | 2 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 2 | 2 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 2 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 2 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 4 | 2 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 4 | 2 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| mixta | 4 | 2 | 4 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| mixta | 4 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 2 | 6 | DISPATCHED: Enviado por menor ETA=73861167 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=6 |
| mixta | 1 | 2 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 1 | 2 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 3 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 4 | 3 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 4 | 3 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| mixta | 4 | 3 | 4 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| mixta | 4 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 4 | 3 | 6 | DISPATCHED: Enviado por menor ETA=74373746 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=6 |
| mixta | 1 | 3 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 1 | 3 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 1 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 3 | 1 | DISPATCHED: Enviado por menor ETA=150000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| mixta | 2 | 3 | 2 | DISPATCHED: Enviado por menor ETA=25000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| mixta | 2 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| mixta | 2 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 1 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 1 | 1 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 1 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 2 | 1 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 2 | 1 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 1 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 1 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 4 | 1 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 4 | 1 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| opuestos | 4 | 1 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| opuestos | 4 | 1 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 1 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 2 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 2 | 2 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 2 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 2 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 4 | 2 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 4 | 2 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| opuestos | 4 | 2 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| opuestos | 4 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 2 | 6 | DISPATCHED: Enviado por menor ETA=24108208 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=6 |
| opuestos | 1 | 2 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 1 | 2 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 2 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 2 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 2 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 2 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 3 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 4 | 3 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 4 | 3 | 3 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=3; commandId=3 |
| opuestos | 4 | 3 | 4 | DISPATCHED: Enviado por menor ETA=100000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=4; commandId=4 |
| opuestos | 4 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 4 | 3 | 6 | DISPATCHED: Enviado por menor ETA=24706249 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=6 |
| opuestos | 1 | 3 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 1 | 3 | 2 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 1 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 3 | 1 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=1; commandId=1 |
| opuestos | 2 | 3 | 2 | DISPATCHED: Enviado por menor ETA=50000000 ns; desempate por ID ascendente. Admisión y servicio pendientes de confirmación. Destinatario=2; commandId=2 |
| opuestos | 2 | 3 | 3 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 3 | 4 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 3 | 5 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |
| opuestos | 2 | 3 | 6 | PENDING: Conservada: no hay cabinas disponibles, confirmadas o estimables para despachar. |

## Observaciones por llamada

Tiempos relativos al inicio de la ejecución, en nanosegundos del reloj monotónico. Espera = atendida - enviada; son timestamps de servicio, no instantes de extracción de la cola.

| Carga | N | Repetición | Índice | Piso | Sentido | Cabina | Comando | Enviada ns | Atendida ns | Espera ns |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| subida | 1 | 1 | 1 | 2 | UP | 1 | 1 | 5067 | 50896090 | 50891023 |
| subida | 1 | 1 | 2 | 3 | UP | 1 | 2 | 135513 | 86196298 | 86060785 |
| subida | 1 | 1 | 3 | 4 | UP | 1 | 3 | 143495 | 121521512 | 121378017 |
| subida | 1 | 1 | 4 | 5 | UP | 1 | 4 | 149243 | 156857261 | 156708018 |
| subida | 1 | 1 | 5 | 6 | UP | 1 | 5 | 154521 | 192273824 | 192119303 |
| subida | 1 | 1 | 6 | 7 | UP | 1 | 6 | 159519 | 227515593 | 227356074 |
| subida | 2 | 1 | 1 | 2 | UP | 1 | 1 | 691 | 26146058 | 26145367 |
| subida | 2 | 1 | 2 | 3 | UP | 2 | 2 | 34912 | 53324937 | 53290025 |
| subida | 2 | 1 | 3 | 4 | UP | 1 | 3 | 38237 | 86577307 | 86539070 |
| subida | 2 | 1 | 4 | 5 | UP | 2 | 4 | 40390 | 113668119 | 113627729 |
| subida | 2 | 1 | 5 | 6 | UP | 1 | 5 | 42443 | 146949914 | 146907471 |
| subida | 2 | 1 | 6 | 7 | UP | 2 | 6 | 57055 | 174030880 | 173973825 |
| subida | 4 | 1 | 1 | 2 | UP | 1 | 1 | 541 | 25981899 | 25981358 |
| subida | 4 | 1 | 2 | 3 | UP | 2 | 2 | 18959 | 52158722 | 52139763 |
| subida | 4 | 1 | 3 | 4 | UP | 3 | 3 | 21142 | 78789686 | 78768544 |
| subida | 4 | 1 | 4 | 5 | UP | 4 | 4 | 22404 | 104017197 | 103994793 |
| subida | 4 | 1 | 5 | 6 | UP | 1 | 5 | 23466 | 136664041 | 136640575 |
| subida | 4 | 1 | 6 | 7 | UP | 2 | 6 | 24567 | 162842513 | 162817946 |
| subida | 2 | 2 | 1 | 2 | UP | 1 | 1 | 681 | 25734964 | 25734283 |
| subida | 2 | 2 | 2 | 3 | UP | 2 | 2 | 14983 | 51112310 | 51097327 |
| subida | 2 | 2 | 3 | 4 | UP | 1 | 3 | 16365 | 86104411 | 86088046 |
| subida | 2 | 2 | 4 | 5 | UP | 2 | 4 | 17356 | 111451105 | 111433749 |
| subida | 2 | 2 | 5 | 6 | UP | 1 | 5 | 18188 | 146947026 | 146928838 |
| subida | 2 | 2 | 6 | 7 | UP | 2 | 6 | 18969 | 172156921 | 172137952 |
| subida | 4 | 2 | 1 | 2 | UP | 1 | 1 | 571 | 25869789 | 25869218 |
| subida | 4 | 2 | 2 | 3 | UP | 2 | 2 | 16865 | 52062274 | 52045409 |
| subida | 4 | 2 | 3 | 4 | UP | 3 | 3 | 18097 | 77609566 | 77591469 |
| subida | 4 | 2 | 4 | 5 | UP | 4 | 4 | 18998 | 102878284 | 102859286 |
| subida | 4 | 2 | 5 | 6 | UP | 1 | 5 | 19669 | 136531812 | 136512143 |
| subida | 4 | 2 | 6 | 7 | UP | 2 | 6 | 20360 | 162733930 | 162713570 |
| subida | 1 | 2 | 1 | 2 | UP | 1 | 1 | 611 | 25509030 | 25508419 |
| subida | 1 | 2 | 2 | 3 | UP | 1 | 2 | 63755 | 60767237 | 60703482 |
| subida | 1 | 2 | 3 | 4 | UP | 1 | 3 | 65318 | 96052641 | 95987323 |
| subida | 1 | 2 | 4 | 5 | UP | 1 | 4 | 66129 | 131337928 | 131271799 |
| subida | 1 | 2 | 5 | 6 | UP | 1 | 5 | 66780 | 166536721 | 166469941 |
| subida | 1 | 2 | 6 | 7 | UP | 1 | 6 | 67451 | 201756359 | 201688908 |
| subida | 4 | 3 | 1 | 2 | UP | 1 | 1 | 481 | 25730512 | 25730031 |
| subida | 4 | 3 | 2 | 3 | UP | 2 | 2 | 15824 | 51241143 | 51225319 |
| subida | 4 | 3 | 3 | 4 | UP | 3 | 3 | 19760 | 76599551 | 76579791 |
| subida | 4 | 3 | 4 | 5 | UP | 4 | 4 | 20651 | 102061274 | 102040623 |
| subida | 4 | 3 | 5 | 6 | UP | 1 | 5 | 21332 | 136343969 | 136322637 |
| subida | 4 | 3 | 6 | 7 | UP | 2 | 6 | 22003 | 161865332 | 161843329 |
| subida | 1 | 3 | 1 | 2 | UP | 1 | 1 | 541 | 25563210 | 25562669 |
| subida | 1 | 3 | 2 | 3 | UP | 1 | 2 | 19279 | 60789021 | 60769742 |
| subida | 1 | 3 | 3 | 4 | UP | 1 | 3 | 20801 | 96022527 | 96001726 |
| subida | 1 | 3 | 4 | 5 | UP | 1 | 4 | 21742 | 131328691 | 131306949 |
| subida | 1 | 3 | 5 | 6 | UP | 1 | 5 | 22514 | 166557032 | 166534518 |
| subida | 1 | 3 | 6 | 7 | UP | 1 | 6 | 23385 | 201788080 | 201764695 |
| subida | 2 | 3 | 1 | 2 | UP | 1 | 1 | 641 | 25468287 | 25467646 |
| subida | 2 | 3 | 2 | 3 | UP | 2 | 2 | 4857 | 50743511 | 50738654 |
| subida | 2 | 3 | 3 | 4 | UP | 1 | 3 | 5828 | 85793257 | 85787429 |
| subida | 2 | 3 | 4 | 5 | UP | 2 | 4 | 6519 | 111070143 | 111063624 |
| subida | 2 | 3 | 5 | 6 | UP | 1 | 5 | 7160 | 146104562 | 146097402 |
| subida | 2 | 3 | 6 | 7 | UP | 2 | 6 | 8222 | 171370370 | 171362148 |
| mixta | 1 | 1 | 1 | 7 | DOWN | 1 | 1 | 611 | 181732645 | 181732034 |
| mixta | 1 | 1 | 2 | 2 | UP | 1 | 2 | 14792 | 25473085 | 25458293 |
| mixta | 1 | 1 | 3 | 5 | DOWN | 1 | 3 | 15874 | 252648382 | 252632508 |
| mixta | 1 | 1 | 4 | 3 | UP | 1 | 4 | 16655 | 60695905 | 60679250 |
| mixta | 1 | 1 | 5 | 6 | DOWN | 1 | 5 | 17356 | 217369282 | 217351926 |
| mixta | 1 | 1 | 6 | 4 | UP | 1 | 6 | 21121 | 96217124 | 96196003 |
| mixta | 2 | 1 | 1 | 7 | DOWN | 1 | 1 | 441 | 171464888 | 171464447 |
| mixta | 2 | 1 | 2 | 2 | UP | 2 | 2 | 16134 | 25993961 | 25977827 |
| mixta | 2 | 1 | 3 | 5 | DOWN | 2 | 3 | 17416 | 111502816 | 111485400 |
| mixta | 2 | 1 | 4 | 3 | UP | 1 | 4 | 18187 | 50697342 | 50679155 |
| mixta | 2 | 1 | 5 | 6 | DOWN | 2 | 5 | 18898 | 146712194 | 146693296 |
| mixta | 2 | 1 | 6 | 4 | UP | 1 | 6 | 19600 | 85958560 | 85938960 |
| mixta | 4 | 1 | 1 | 7 | DOWN | 1 | 1 | 601 | 161611202 | 161610601 |
| mixta | 4 | 1 | 2 | 2 | UP | 2 | 2 | 20000 | 25931399 | 25911399 |
| mixta | 4 | 1 | 3 | 5 | DOWN | 3 | 3 | 21483 | 101780939 | 101759456 |
| mixta | 4 | 1 | 4 | 3 | UP | 4 | 4 | 22214 | 51889340 | 51867126 |
| mixta | 4 | 1 | 5 | 6 | DOWN | 2 | 5 | 22915 | 136514822 | 136491907 |
| mixta | 4 | 1 | 6 | 4 | UP | 1 | 6 | 23646 | 76042845 | 76019199 |
| mixta | 2 | 2 | 1 | 7 | DOWN | 1 | 1 | 541 | 171548523 | 171547982 |
| mixta | 2 | 2 | 2 | 2 | UP | 2 | 2 | 18608 | 25773285 | 25754677 |
| mixta | 2 | 2 | 3 | 5 | DOWN | 2 | 3 | 19699 | 111299596 | 111279897 |
| mixta | 2 | 2 | 4 | 3 | UP | 1 | 4 | 20440 | 50807586 | 50787146 |
| mixta | 2 | 2 | 5 | 6 | DOWN | 2 | 5 | 21011 | 146536810 | 146515799 |
| mixta | 2 | 2 | 6 | 4 | UP | 1 | 6 | 21622 | 86078905 | 86057283 |
| mixta | 4 | 2 | 1 | 7 | DOWN | 1 | 1 | 701 | 161566092 | 161565391 |
| mixta | 4 | 2 | 2 | 2 | UP | 2 | 2 | 20781 | 25983450 | 25962669 |
| mixta | 4 | 2 | 3 | 5 | DOWN | 3 | 3 | 21963 | 102253010 | 102231047 |
| mixta | 4 | 2 | 4 | 3 | UP | 4 | 4 | 22654 | 51951341 | 51928687 |
| mixta | 4 | 2 | 5 | 6 | DOWN | 2 | 5 | 23245 | 136542372 | 136519127 |
| mixta | 4 | 2 | 6 | 4 | UP | 1 | 6 | 23776 | 75970853 | 75947077 |
| mixta | 1 | 2 | 1 | 7 | DOWN | 1 | 1 | 681 | 182241119 | 182240438 |
| mixta | 1 | 2 | 2 | 2 | UP | 1 | 2 | 21703 | 26112575 | 26090872 |
| mixta | 1 | 2 | 3 | 5 | DOWN | 1 | 3 | 22965 | 252855535 | 252832570 |
| mixta | 1 | 2 | 4 | 3 | UP | 1 | 4 | 23696 | 61379217 | 61355521 |
| mixta | 1 | 2 | 5 | 6 | DOWN | 1 | 5 | 24327 | 217526865 | 217502538 |
| mixta | 1 | 2 | 6 | 4 | UP | 1 | 6 | 24968 | 96649502 | 96624534 |
| mixta | 4 | 3 | 1 | 7 | DOWN | 1 | 1 | 581 | 161567872 | 161567291 |
| mixta | 4 | 3 | 2 | 2 | UP | 2 | 2 | 17777 | 25698664 | 25680887 |
| mixta | 4 | 3 | 3 | 5 | DOWN | 3 | 3 | 18818 | 101156862 | 101138044 |
| mixta | 4 | 3 | 4 | 3 | UP | 4 | 4 | 19469 | 51267202 | 51247733 |
| mixta | 4 | 3 | 5 | 6 | DOWN | 2 | 5 | 20070 | 136406193 | 136386123 |
| mixta | 4 | 3 | 6 | 4 | UP | 1 | 6 | 20661 | 75903437 | 75882776 |
| mixta | 1 | 3 | 1 | 7 | DOWN | 1 | 1 | 561 | 181382500 | 181381939 |
| mixta | 1 | 3 | 2 | 2 | UP | 1 | 2 | 21242 | 25445609 | 25424367 |
| mixta | 1 | 3 | 3 | 5 | DOWN | 1 | 3 | 22524 | 251975064 | 251952540 |
| mixta | 1 | 3 | 4 | 3 | UP | 1 | 4 | 23305 | 60663723 | 60640418 |
| mixta | 1 | 3 | 5 | 6 | DOWN | 1 | 5 | 24066 | 216637565 | 216613499 |
| mixta | 1 | 3 | 6 | 4 | UP | 1 | 6 | 24827 | 95876529 | 95851702 |
| mixta | 2 | 3 | 1 | 7 | DOWN | 1 | 1 | 521 | 171518633 | 171518112 |
| mixta | 2 | 3 | 2 | 2 | UP | 2 | 2 | 17506 | 25707637 | 25690131 |
| mixta | 2 | 3 | 3 | 5 | DOWN | 1 | 3 | 18618 | 231969567 | 231950949 |
| mixta | 2 | 3 | 4 | 3 | UP | 1 | 4 | 19319 | 50735078 | 50715759 |
| mixta | 2 | 3 | 5 | 6 | DOWN | 2 | 5 | 19830 | 136284409 | 136264579 |
| mixta | 2 | 3 | 6 | 4 | UP | 1 | 6 | 20331 | 86001556 | 85981225 |
| opuestos | 1 | 1 | 1 | 3 | UP | 1 | 1 | 661 | 50653812 | 50653151 |
| opuestos | 1 | 1 | 2 | 3 | DOWN | 1 | 2 | 16605 | 60749722 | 60733117 |
| opuestos | 1 | 1 | 3 | 5 | UP | 1 | 3 | 19259 | 121117868 | 121098609 |
| opuestos | 1 | 1 | 4 | 5 | DOWN | 1 | 4 | 20000 | 131233533 | 131213533 |
| opuestos | 1 | 1 | 5 | 7 | DOWN | 1 | 5 | 20541 | 353049669 | 353029128 |
| opuestos | 1 | 1 | 6 | 2 | UP | 1 | 6 | 21072 | 217035861 | 217014789 |
| opuestos | 2 | 1 | 1 | 3 | UP | 1 | 1 | 761 | 60708861 | 60708100 |
| opuestos | 2 | 1 | 2 | 3 | DOWN | 2 | 2 | 4927 | 51185483 | 51180556 |
| opuestos | 2 | 1 | 3 | 5 | UP | 1 | 3 | 5828 | 121058083 | 121052255 |
| opuestos | 2 | 1 | 4 | 5 | DOWN | 1 | 4 | 6529 | 131174635 | 131168106 |
| opuestos | 2 | 1 | 5 | 7 | DOWN | 2 | 5 | 7100 | 162236441 | 162229341 |
| opuestos | 2 | 1 | 6 | 2 | UP | 1 | 6 | 7711 | 25439364 | 25431653 |
| opuestos | 4 | 1 | 1 | 3 | UP | 1 | 1 | 811 | 51020563 | 51019752 |
| opuestos | 4 | 1 | 2 | 3 | DOWN | 2 | 2 | 5177 | 61179373 | 61174196 |
| opuestos | 4 | 1 | 3 | 5 | UP | 3 | 3 | 6069 | 101657285 | 101651216 |
| opuestos | 4 | 1 | 4 | 5 | DOWN | 4 | 4 | 6690 | 101781697 | 101775007 |
| opuestos | 4 | 1 | 5 | 7 | DOWN | 1 | 5 | 7240 | 162112507 | 162105267 |
| opuestos | 4 | 1 | 6 | 2 | UP | 2 | 6 | 7751 | 25979715 | 25971964 |
| opuestos | 2 | 2 | 1 | 3 | UP | 1 | 1 | 621 | 50633032 | 50632411 |
| opuestos | 2 | 2 | 2 | 3 | DOWN | 2 | 2 | 22945 | 51082071 | 51059126 |
| opuestos | 2 | 2 | 3 | 5 | UP | 1 | 3 | 25348 | 171404611 | 171379263 |
| opuestos | 2 | 2 | 4 | 5 | DOWN | 2 | 4 | 26891 | 111393642 | 111366751 |
| opuestos | 2 | 2 | 5 | 7 | DOWN | 1 | 5 | 27682 | 231996288 | 231968606 |
| opuestos | 2 | 2 | 6 | 2 | UP | 1 | 6 | 28503 | 85855919 | 85827416 |
| opuestos | 4 | 2 | 1 | 3 | UP | 1 | 1 | 661 | 50708860 | 50708199 |
| opuestos | 4 | 2 | 2 | 3 | DOWN | 2 | 2 | 22854 | 51001009 | 50978155 |
| opuestos | 4 | 2 | 3 | 5 | UP | 3 | 3 | 24477 | 111833606 | 111809129 |
| opuestos | 4 | 2 | 4 | 5 | DOWN | 4 | 4 | 25468 | 101754576 | 101729108 |
| opuestos | 4 | 2 | 5 | 7 | DOWN | 1 | 5 | 26310 | 161566618 | 161540308 |
| opuestos | 4 | 2 | 6 | 2 | UP | 3 | 6 | 27031 | 26344477 | 26317446 |
| opuestos | 1 | 2 | 1 | 3 | UP | 1 | 1 | 721 | 50911564 | 50910843 |
| opuestos | 1 | 2 | 2 | 3 | DOWN | 1 | 2 | 18157 | 61273293 | 61255136 |
| opuestos | 1 | 2 | 3 | 5 | UP | 1 | 3 | 22274 | 123187797 | 123165523 |
| opuestos | 1 | 2 | 4 | 5 | DOWN | 1 | 4 | 22955 | 133402857 | 133379902 |
| opuestos | 1 | 2 | 5 | 7 | DOWN | 1 | 5 | 23526 | 355078442 | 355054916 |
| opuestos | 1 | 2 | 6 | 2 | UP | 1 | 6 | 24066 | 218967934 | 218943868 |
| opuestos | 4 | 3 | 1 | 3 | UP | 1 | 1 | 571 | 50685339 | 50684768 |
| opuestos | 4 | 3 | 2 | 3 | DOWN | 2 | 2 | 20170 | 60935462 | 60915292 |
| opuestos | 4 | 3 | 3 | 5 | UP | 3 | 3 | 21402 | 101200999 | 101179597 |
| opuestos | 4 | 3 | 4 | 5 | DOWN | 4 | 4 | 22123 | 101200964 | 101178841 |
| opuestos | 4 | 3 | 5 | 7 | DOWN | 1 | 5 | 22704 | 161670659 | 161647955 |
| opuestos | 4 | 3 | 6 | 2 | UP | 2 | 6 | 23285 | 25688643 | 25665358 |
| opuestos | 1 | 3 | 1 | 3 | UP | 1 | 1 | 881 | 50819451 | 50818570 |
| opuestos | 1 | 3 | 2 | 3 | DOWN | 1 | 2 | 24566 | 61152487 | 61127921 |
| opuestos | 1 | 3 | 3 | 5 | UP | 1 | 3 | 25768 | 121583613 | 121557845 |
| opuestos | 1 | 3 | 4 | 5 | DOWN | 1 | 4 | 57666 | 131737709 | 131680043 |
| opuestos | 1 | 3 | 5 | 7 | DOWN | 1 | 5 | 58597 | 354275743 | 354217146 |
| opuestos | 1 | 3 | 6 | 2 | UP | 1 | 6 | 59369 | 218101890 | 218042521 |
| opuestos | 2 | 3 | 1 | 3 | UP | 1 | 1 | 511 | 53511990 | 53511479 |
| opuestos | 2 | 3 | 2 | 3 | DOWN | 2 | 2 | 16425 | 53768290 | 53751865 |
| opuestos | 2 | 3 | 3 | 5 | UP | 1 | 3 | 17566 | 176637624 | 176620058 |
| opuestos | 2 | 3 | 4 | 5 | DOWN | 1 | 4 | 18237 | 186779594 | 186761357 |
| opuestos | 2 | 3 | 5 | 7 | DOWN | 2 | 5 | 18748 | 164552901 | 164534153 |
| opuestos | 2 | 3 | 6 | 2 | UP | 1 | 6 | 19279 | 88774861 | 88755582 |

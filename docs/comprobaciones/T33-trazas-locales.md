# T33 — Trazas locales de integración

- **Fecha:** 2026-10-27.
- **Entorno:** OpenJDK 17.0.20, Swing headless.
- **Origen:** salida real de DirectionPickupScenarios sobre la producción de T32, sin cambios.

Los tiempos relativos y UUID pertenecen a esta ejecución. No son valores fijos para comparar futuras ejecuciones. Cada bloque corresponde a un trabajador de cabina nuevo; las secuencias mayores que 1 al comienzo de un bloque omiten la preparación de posición.

```text
T33_TRACE scenario=compatible-en-subida
seq=1 t_ms=31.695 type=COMMAND_ACCEPTED floor=1 next=- direction=UP phase=IDLE command=1 request=adf0a7ba-4718-4e5d-aeb5-c6387a808b84
seq=2 t_ms=32.120 type=DEPARTED floor=1 next=2 direction=UP phase=MOVING command=- request=-
seq=3 t_ms=46.734 type=COMMAND_ACCEPTED floor=1 next=2 direction=UP phase=MOVING command=2 request=5034f997-ec72-4f9b-b935-563a86baa384
seq=4 t_ms=232.308 type=ARRIVED floor=2 next=- direction=UP phase=IDLE command=- request=-
seq=5 t_ms=232.308 type=DEPARTED floor=2 next=3 direction=UP phase=MOVING command=- request=-
seq=6 t_ms=432.549 type=ARRIVED floor=3 next=- direction=UP phase=IDLE command=- request=-
seq=7 t_ms=432.549 type=STOP_STARTED floor=3 next=- direction=UP phase=DWELLING command=- request=-
seq=8 t_ms=432.549 type=COMMAND_COMPLETED floor=3 next=- direction=UP phase=DWELLING command=2 request=5034f997-ec72-4f9b-b935-563a86baa384
seq=9 t_ms=472.824 type=STOP_ENDED floor=3 next=- direction=UP phase=IDLE command=- request=-
seq=10 t_ms=472.824 type=DEPARTED floor=3 next=4 direction=UP phase=MOVING command=- request=-
seq=11 t_ms=673.000 type=ARRIVED floor=4 next=- direction=UP phase=IDLE command=- request=-
seq=12 t_ms=673.000 type=DEPARTED floor=4 next=5 direction=UP phase=MOVING command=- request=-
seq=13 t_ms=873.237 type=ARRIVED floor=5 next=- direction=UP phase=IDLE command=- request=-
seq=14 t_ms=873.237 type=DEPARTED floor=5 next=6 direction=UP phase=MOVING command=- request=-
seq=15 t_ms=1073.474 type=ARRIVED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=16 t_ms=1073.474 type=STOP_STARTED floor=6 next=- direction=UP phase=DWELLING command=- request=-
seq=17 t_ms=1073.474 type=COMMAND_COMPLETED floor=6 next=- direction=UP phase=DWELLING command=1 request=adf0a7ba-4718-4e5d-aeb5-c6387a808b84
seq=18 t_ms=1113.683 type=STOP_ENDED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=19 t_ms=1116.872 type=WORKER_TERMINATED floor=6 next=- direction=UP phase=IDLE command=- request=-
T33_TRACE scenario=contraria-espera-regreso
seq=1 t_ms=1.192 type=COMMAND_ACCEPTED floor=1 next=- direction=UP phase=IDLE command=1 request=498afab2-5ef7-4807-8aba-28788c0b7e39
seq=2 t_ms=1.221 type=DEPARTED floor=1 next=2 direction=UP phase=MOVING command=- request=-
seq=3 t_ms=2.591 type=COMMAND_ACCEPTED floor=1 next=2 direction=UP phase=MOVING command=2 request=3a8a7b7b-a0e0-43a3-af52-22d06ffc28f3
seq=4 t_ms=201.422 type=ARRIVED floor=2 next=- direction=UP phase=IDLE command=- request=-
seq=5 t_ms=201.422 type=DEPARTED floor=2 next=3 direction=UP phase=MOVING command=- request=-
seq=6 t_ms=401.589 type=ARRIVED floor=3 next=- direction=UP phase=IDLE command=- request=-
seq=7 t_ms=401.589 type=DEPARTED floor=3 next=4 direction=UP phase=MOVING command=- request=-
seq=8 t_ms=602.101 type=ARRIVED floor=4 next=- direction=UP phase=IDLE command=- request=-
seq=9 t_ms=602.101 type=DEPARTED floor=4 next=5 direction=UP phase=MOVING command=- request=-
seq=10 t_ms=802.332 type=ARRIVED floor=5 next=- direction=UP phase=IDLE command=- request=-
seq=11 t_ms=802.332 type=DEPARTED floor=5 next=6 direction=UP phase=MOVING command=- request=-
seq=12 t_ms=1002.507 type=ARRIVED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=13 t_ms=1002.507 type=STOP_STARTED floor=6 next=- direction=UP phase=DWELLING command=- request=-
seq=14 t_ms=1002.507 type=COMMAND_COMPLETED floor=6 next=- direction=UP phase=DWELLING command=1 request=498afab2-5ef7-4807-8aba-28788c0b7e39
seq=15 t_ms=1042.669 type=STOP_ENDED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=16 t_ms=1042.669 type=DIRECTION_CHANGED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=17 t_ms=1042.669 type=DEPARTED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=18 t_ms=1243.050 type=ARRIVED floor=5 next=- direction=DOWN phase=IDLE command=- request=-
seq=19 t_ms=1243.050 type=DEPARTED floor=5 next=4 direction=DOWN phase=MOVING command=- request=-
seq=20 t_ms=1443.229 type=ARRIVED floor=4 next=- direction=DOWN phase=IDLE command=- request=-
seq=21 t_ms=1443.229 type=DEPARTED floor=4 next=3 direction=DOWN phase=MOVING command=- request=-
seq=22 t_ms=1643.387 type=ARRIVED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=23 t_ms=1643.387 type=STOP_STARTED floor=3 next=- direction=DOWN phase=DWELLING command=- request=-
seq=24 t_ms=1643.387 type=COMMAND_COMPLETED floor=3 next=- direction=DOWN phase=DWELLING command=2 request=3a8a7b7b-a0e0-43a3-af52-22d06ffc28f3
seq=25 t_ms=1683.569 type=STOP_ENDED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=26 t_ms=1684.216 type=WORKER_TERMINATED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
T33_TRACE scenario=dos-sentidos-en-piso-3
seq=1 t_ms=0.824 type=COMMAND_ACCEPTED floor=1 next=- direction=UP phase=IDLE command=1 request=42cf8960-a1e9-4c29-b7fc-cea5ac773030
seq=2 t_ms=0.839 type=DEPARTED floor=1 next=2 direction=UP phase=MOVING command=- request=-
seq=3 t_ms=1.384 type=COMMAND_ACCEPTED floor=1 next=2 direction=UP phase=MOVING command=2 request=bfd6becb-4cec-49a1-98d4-5ca4e57ebf51
seq=4 t_ms=2.156 type=COMMAND_ACCEPTED floor=1 next=2 direction=UP phase=MOVING command=3 request=b73babd6-23be-4d82-99b7-0c082a07239d
seq=5 t_ms=201.015 type=ARRIVED floor=2 next=- direction=UP phase=IDLE command=- request=-
seq=6 t_ms=201.015 type=DEPARTED floor=2 next=3 direction=UP phase=MOVING command=- request=-
seq=7 t_ms=401.172 type=ARRIVED floor=3 next=- direction=UP phase=IDLE command=- request=-
seq=8 t_ms=401.172 type=STOP_STARTED floor=3 next=- direction=UP phase=DWELLING command=- request=-
seq=9 t_ms=401.172 type=COMMAND_COMPLETED floor=3 next=- direction=UP phase=DWELLING command=2 request=bfd6becb-4cec-49a1-98d4-5ca4e57ebf51
seq=10 t_ms=441.472 type=STOP_ENDED floor=3 next=- direction=UP phase=IDLE command=- request=-
seq=11 t_ms=441.472 type=DEPARTED floor=3 next=4 direction=UP phase=MOVING command=- request=-
seq=12 t_ms=641.674 type=ARRIVED floor=4 next=- direction=UP phase=IDLE command=- request=-
seq=13 t_ms=641.674 type=DEPARTED floor=4 next=5 direction=UP phase=MOVING command=- request=-
seq=14 t_ms=841.830 type=ARRIVED floor=5 next=- direction=UP phase=IDLE command=- request=-
seq=15 t_ms=841.830 type=DEPARTED floor=5 next=6 direction=UP phase=MOVING command=- request=-
seq=16 t_ms=1042.006 type=ARRIVED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=17 t_ms=1042.006 type=STOP_STARTED floor=6 next=- direction=UP phase=DWELLING command=- request=-
seq=18 t_ms=1042.006 type=COMMAND_COMPLETED floor=6 next=- direction=UP phase=DWELLING command=1 request=42cf8960-a1e9-4c29-b7fc-cea5ac773030
seq=19 t_ms=1082.226 type=STOP_ENDED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=20 t_ms=1082.226 type=DIRECTION_CHANGED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=21 t_ms=1082.226 type=DEPARTED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=22 t_ms=1282.387 type=ARRIVED floor=5 next=- direction=DOWN phase=IDLE command=- request=-
seq=23 t_ms=1282.387 type=DEPARTED floor=5 next=4 direction=DOWN phase=MOVING command=- request=-
seq=24 t_ms=1482.611 type=ARRIVED floor=4 next=- direction=DOWN phase=IDLE command=- request=-
seq=25 t_ms=1482.611 type=DEPARTED floor=4 next=3 direction=DOWN phase=MOVING command=- request=-
seq=26 t_ms=1682.785 type=ARRIVED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=27 t_ms=1682.785 type=STOP_STARTED floor=3 next=- direction=DOWN phase=DWELLING command=- request=-
seq=28 t_ms=1682.785 type=COMMAND_COMPLETED floor=3 next=- direction=DOWN phase=DWELLING command=3 request=b73babd6-23be-4d82-99b7-0c082a07239d
seq=29 t_ms=1722.970 type=STOP_ENDED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=30 t_ms=1723.821 type=WORKER_TERMINATED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
T33_TRACE scenario=compatible-en-bajada
seq=17 t_ms=1242.812 type=COMMAND_ACCEPTED floor=7 next=- direction=UP phase=IDLE command=2 request=443df516-de3e-4357-9e4e-57a65eb4fb56
seq=18 t_ms=1242.826 type=DIRECTION_CHANGED floor=7 next=6 direction=DOWN phase=MOVING command=- request=-
seq=19 t_ms=1242.826 type=DEPARTED floor=7 next=6 direction=DOWN phase=MOVING command=- request=-
seq=20 t_ms=1243.550 type=COMMAND_ACCEPTED floor=7 next=6 direction=DOWN phase=MOVING command=3 request=6dc76240-4a72-4920-a2f7-55139afbe314
seq=21 t_ms=1443.169 type=ARRIVED floor=6 next=- direction=DOWN phase=IDLE command=- request=-
seq=22 t_ms=1443.169 type=DEPARTED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=23 t_ms=1643.356 type=ARRIVED floor=5 next=- direction=DOWN phase=IDLE command=- request=-
seq=24 t_ms=1643.356 type=STOP_STARTED floor=5 next=- direction=DOWN phase=DWELLING command=- request=-
seq=25 t_ms=1643.356 type=COMMAND_COMPLETED floor=5 next=- direction=DOWN phase=DWELLING command=3 request=6dc76240-4a72-4920-a2f7-55139afbe314
seq=26 t_ms=1683.528 type=STOP_ENDED floor=5 next=- direction=DOWN phase=IDLE command=- request=-
seq=27 t_ms=1683.528 type=DEPARTED floor=5 next=4 direction=DOWN phase=MOVING command=- request=-
seq=28 t_ms=1883.692 type=ARRIVED floor=4 next=- direction=DOWN phase=IDLE command=- request=-
seq=29 t_ms=1883.692 type=DEPARTED floor=4 next=3 direction=DOWN phase=MOVING command=- request=-
seq=30 t_ms=2083.838 type=ARRIVED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=31 t_ms=2083.838 type=DEPARTED floor=3 next=2 direction=DOWN phase=MOVING command=- request=-
seq=32 t_ms=2283.986 type=ARRIVED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
seq=33 t_ms=2283.986 type=STOP_STARTED floor=2 next=- direction=DOWN phase=DWELLING command=- request=-
seq=34 t_ms=2283.986 type=COMMAND_COMPLETED floor=2 next=- direction=DOWN phase=DWELLING command=2 request=443df516-de3e-4357-9e4e-57a65eb4fb56
seq=35 t_ms=2324.147 type=STOP_ENDED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
seq=36 t_ms=2324.763 type=WORKER_TERMINATED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
T33_TRACE scenario=llamada-detras-del-barrido
seq=9 t_ms=441.833 type=COMMAND_ACCEPTED floor=3 next=- direction=UP phase=IDLE command=2 request=df88a0a2-124c-40c6-aa0a-0ffb31be60f8
seq=10 t_ms=441.842 type=DEPARTED floor=3 next=4 direction=UP phase=MOVING command=- request=-
seq=11 t_ms=442.811 type=COMMAND_ACCEPTED floor=3 next=4 direction=UP phase=MOVING command=3 request=55a49d18-dc6c-4b68-8967-721628a3e799
seq=12 t_ms=642.008 type=ARRIVED floor=4 next=- direction=UP phase=IDLE command=- request=-
seq=13 t_ms=642.008 type=DEPARTED floor=4 next=5 direction=UP phase=MOVING command=- request=-
seq=14 t_ms=842.201 type=ARRIVED floor=5 next=- direction=UP phase=IDLE command=- request=-
seq=15 t_ms=842.201 type=DEPARTED floor=5 next=6 direction=UP phase=MOVING command=- request=-
seq=16 t_ms=1042.344 type=ARRIVED floor=6 next=- direction=UP phase=IDLE command=- request=-
seq=17 t_ms=1042.344 type=DEPARTED floor=6 next=7 direction=UP phase=MOVING command=- request=-
seq=18 t_ms=1242.561 type=ARRIVED floor=7 next=- direction=UP phase=IDLE command=- request=-
seq=19 t_ms=1242.561 type=STOP_STARTED floor=7 next=- direction=UP phase=DWELLING command=- request=-
seq=20 t_ms=1242.561 type=COMMAND_COMPLETED floor=7 next=- direction=UP phase=DWELLING command=2 request=df88a0a2-124c-40c6-aa0a-0ffb31be60f8
seq=21 t_ms=1282.923 type=STOP_ENDED floor=7 next=- direction=UP phase=IDLE command=- request=-
seq=22 t_ms=1282.923 type=DIRECTION_CHANGED floor=7 next=6 direction=DOWN phase=MOVING command=- request=-
seq=23 t_ms=1282.923 type=DEPARTED floor=7 next=6 direction=DOWN phase=MOVING command=- request=-
seq=24 t_ms=1483.088 type=ARRIVED floor=6 next=- direction=DOWN phase=IDLE command=- request=-
seq=25 t_ms=1483.088 type=DEPARTED floor=6 next=5 direction=DOWN phase=MOVING command=- request=-
seq=26 t_ms=1683.270 type=ARRIVED floor=5 next=- direction=DOWN phase=IDLE command=- request=-
seq=27 t_ms=1683.270 type=DEPARTED floor=5 next=4 direction=DOWN phase=MOVING command=- request=-
seq=28 t_ms=1883.632 type=ARRIVED floor=4 next=- direction=DOWN phase=IDLE command=- request=-
seq=29 t_ms=1883.632 type=DEPARTED floor=4 next=3 direction=DOWN phase=MOVING command=- request=-
seq=30 t_ms=2083.792 type=ARRIVED floor=3 next=- direction=DOWN phase=IDLE command=- request=-
seq=31 t_ms=2083.792 type=DEPARTED floor=3 next=2 direction=DOWN phase=MOVING command=- request=-
seq=32 t_ms=2283.940 type=ARRIVED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
seq=33 t_ms=2283.940 type=STOP_STARTED floor=2 next=- direction=DOWN phase=DWELLING command=- request=-
seq=34 t_ms=2283.940 type=COMMAND_COMPLETED floor=2 next=- direction=DOWN phase=DWELLING command=3 request=55a49d18-dc6c-4b68-8967-721628a3e799
seq=35 t_ms=2324.189 type=STOP_ENDED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
seq=36 t_ms=2325.920 type=WORKER_TERMINATED floor=2 next=- direction=DOWN phase=IDLE command=- request=-
T33: 20 escenarios satisfactorios; 15 de reloj explícito y 5 de integración con actores reales.
```

# T57 — Mediciones locales de tiempo restante

- **Fecha:** 31 de octubre de 2026, Guatemala.
- **Origen:** ejecución final de PausePhaseScenarios dentro de la regresión independiente completa; OpenJDK 17.0.20 en Linux.
- **Resultado:** 18 escenarios satisfactorios; 14 mediciones temporales mostradas abajo.
- **Unidades de tabla:** milisegundos, redondeados a tres decimales para lectura. Las verificaciones se hicieron sobre nanosegundos enteros sin redondear.
- **Alcance:** son mediciones de este entorno; no representan resultados de Windows ni garantías de tiempo real.

R es el restante del snapshot PAUSED, A el tiempo activo desde la referencia congelada hasta ARRIVED/STOP_ENDED y W el tiempo de pared desde antes de solicitar Resume hasta recibir el evento. Se exige R <= A <= R+250 ms y W <= R+250 ms. Los intervalos de pausa previos no forman parte de W. La igualdad de snapshots durante pausa y la ausencia de incremento del presupuesto se comprueban por separado.

| Caso | R restante | A activo tras Resume | W pared tras Resume | A menos R | W menos R | Límite de exceso |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| up-early | 830.891 | 831.090 | 833.770 | 0.198 | 2.879 | 250.000 |
| up-middle | 399.500 | 399.706 | 400.406 | 0.205 | 0.906 | 250.000 |
| up-late | 199.555 | 199.798 | 200.620 | 0.242 | 1.064 | 250.000 |
| down-middle | 399.439 | 399.629 | 400.121 | 0.189 | 0.682 | 250.000 |
| down-late | 199.521 | 199.725 | 200.189 | 0.205 | 0.668 | 250.000 |
| dwell-early | 849.433 | 849.591 | 849.885 | 0.158 | 0.452 | 250.000 |
| dwell-middle | 399.552 | 399.719 | 400.028 | 0.166 | 0.475 | 250.000 |
| dwell-late | 199.592 | 199.812 | 200.088 | 0.219 | 0.495 | 250.000 |
| dwell-down | 398.905 | 399.127 | 399.446 | 0.223 | 0.542 | 250.000 |
| move-four-cycles | 517.676 | 517.876 | 518.503 | 0.201 | 0.827 | 250.000 |
| dwell-four-cycles | 518.302 | 518.471 | 518.738 | 0.170 | 0.437 | 250.000 |
| mixed-moving | 899.074 | 899.294 | 899.727 | 0.220 | 0.653 | 250.000 |
| mixed-dwelling | 1899.153 | 1899.374 | 1899.680 | 0.221 | 0.527 | 250.000 |
| single-floor-dwell | 399.546 | 399.737 | 400.006 | 0.190 | 0.460 | 250.000 |

Mayor exceso activo observado: 0.242482 ms. Mayor exceso de pared observado: 2.878835 ms. Ningún caso terminó anticipadamente ni superó el límite.

Los casos tempranos de movimiento y parada incluyen 1100 ms de pausa antes de Resume, superando su duración original de 1000 ms. Los ciclos repetidos comprueban una fase original de 1200 ms sin reinicio entre las cuatro pausas. mixed-moving y mixed-dwelling proceden de cabinas distintas de una misma sesión, mientras la tercera permanece libre.

## Traza exacta

```text
T57_TIMING|up-early|830891139|831089537|833769974|250000000
T57_TIMING|up-middle|399500356|399705640|400405872|250000000
T57_TIMING|up-late|199555259|199797741|200619550|250000000
T57_TIMING|down-middle|399439249|399628574|400121465|250000000
T57_TIMING|down-late|199520621|199725481|200189008|250000000
T57_TIMING|dwell-early|849433430|849591189|849884971|250000000
T57_TIMING|dwell-middle|399552423|399718806|400027840|250000000
T57_TIMING|dwell-late|199592298|199811591|200087650|250000000
T57_TIMING|dwell-down|398904828|399127475|399446460|250000000
T57_TIMING|move-four-cycles|517675743|517876451|518502617|250000000
T57_TIMING|dwell-four-cycles|518301547|518471222|518738218|250000000
T57_TIMING|mixed-moving|899074220|899293730|899727467|250000000
T57_TIMING|mixed-dwelling|1899152999|1899373886|1899680016|250000000
T57_TIMING|single-floor-dwell|399546157|399736521|400006026|250000000
T57: 18 escenarios de fases y tiempos satisfactorios.
```

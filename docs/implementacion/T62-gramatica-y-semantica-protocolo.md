# T62 — Especificar gramática y semántica del protocolo

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Fase:** 11 — Protocolo textual, extra 3.
- **Base:** proyecto completo proporcionado por David, HEAD `dbea15a` (T61). David declara cerradas las fases 1–10. El contexto anterior registra 16 pruebas T61 y 926 totales, BUILD SUCCESS y árbol limpio; estos resultados no son una ejecución nueva.
- **Estado:** especificación preparada y verificada documentalmente; pendiente de revisión de David. T63 no implementada.

## Resultado

[05 — Protocolo](../05-protocolo.md), versión documental 1.0.0, fija el contrato V1. Define nueve órdenes, ACK/ERROR/terminales, eventos físicos, STATE, framing, tipos, rangos, UUID, correlación, época, registros de retransmisión, autoridad y prioridad de controles. Incluye ejemplos completos y una matriz de pruebas para T63–T66.

Se corrigen las discrepancias del borrador con el proyecto real: UUID de solicitudes en lugar de números y época inicial 0 en lugar de 1. Las palabras reservadas son mayúsculas; los UUID son minúsculas. Se precisa que ACK admite trabajo, mientras COMPLETED de servicio significa comienzo de la parada, no fin del tiempo de espera. Reset invalida trabajo viejo y solo completa físicamente en 1/UP. STATE incorpora fase suspendida, motivo de retención y modo de retorno.

Se especifican errores con precedencia, cabecera recuperable, límite de 1 MiB, replays de órdenes históricas y STATUS, terminales por alias coalescido, autorización por rol del canal y barreras por identidades exactas. El archivo HALL/CABIN conserva su contrato independiente. El estado público del protocolo no sustituye el snapshot preciso del planificador o la animación.

## Código completo y alcance

Esta tarjeta exige un documento que permita implementar extremos compatibles. No añade ni modifica archivos Java: el codec es T63 y el transporte real T64–T65. Se conserva el código completo de la base dentro del ZIP, incluidos 169 archivos Java de producción/pruebas. Se compararon los 200 archivos de src y pom.xml byte a byte con el ZIP recibido: 201 archivos idénticos.

Por tanto no hay código Java nuevo que presentar en pantalla ni archivos .java parciales. Todos los .java existentes están completos en sus rutas originales dentro del ZIP. Se mantienen la herencia real Request/HallRequest/CabinRequest, el paralelismo de manejador/cabinas, Swing, los extras previos, las dependencias y release 17. El documento T62 no demuestra todavía comunicación textual efectiva.

## Archivos de esta tarjeta

| Archivo | Cambio |
| --- | --- |
| docs/05-protocolo.md | Contrato normativo V1 y ejemplos. |
| docs/01-analisis-y-plan.md | Cierre de fase 10 recibido, inicio fase 11 y ejemplos actualizados. |
| docs/02-decisiones.md | Precisiones de identidad, léxico y transporte. |
| docs/03-arquitectura.md | Responsabilidades del codec, canales y estado público. |
| docs/06-pruebas.md | Matriz PR01–PR16 para T63–T66, sin afirmar ejecución. |
| docs/comprobaciones/T61-aceptacion-fase-10.md | Actualización fechada de aceptación, preservando pendientes históricos. |
| docs/implementacion/T62-gramatica-y-semantica-protocolo.md | Esta ficha e incorporación. |
| docs/comprobaciones/T62-validacion-documental.md | Evidencia y límites de la revisión. |

## Incorporación y revisión en Windows

El ZIP contiene pom.xml en su raíz, fuentes completos, recursos, ejemplos y documentación. Excluye .git, target y registros generados. Se extrae sobre el proyecto local, conservando el repositorio Git. Comprobar primero que no hay cambios personales que la extracción pueda sobrescribir.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T62-gramatica-y-semantica-protocolo.zip" -DestinationPath . -Force
git diff --check
git diff --stat
git status --short
code .\docs\05-protocolo.md
```

Revisar especialmente secciones 10 y 11: UUID/época 0, reglas léxicas, prioridades, errores, ACK distinto de servicio y reset con mensajes en vuelo. No hay nueva GUI ni demo ejecutable para T62. No hace falta repetir pruebas de Java por esta tarjeta documental. Si se desea revalidar voluntariamente la base:

```powershell
mvn clean verify
```

El último total comunicado es 926 pruebas; no se añaden pruebas Java en esta tarjeta ni se atribuye a este entorno una nueva construcción Maven. El protocolo se comprobará con nuevas pruebas del codec en T63.

Tras revisar el contrato, marcar T62 como terminada en el plan y sustituir su estado por la confirmación recibida. Commit sugerido:

```powershell
git diff --check
git add -- docs/05-protocolo.md docs/01-analisis-y-plan.md docs/02-decisiones.md docs/03-arquitectura.md docs/06-pruebas.md docs/comprobaciones/T61-aceptacion-fase-10.md docs/implementacion/T62-gramatica-y-semantica-protocolo.md docs/comprobaciones/T62-validacion-documental.md
git diff --cached --check
git diff --cached --stat
git commit -m "T62: especificar gramatica y semantica del protocolo"
git status --short
```

No se crea el commit automáticamente. La siguiente tarea es T63 — Implementar codificador y parser del protocolo.

# T46 — Archivos de ejemplo de solicitudes

Los 22 archivos de esta carpeta son datos de prueba distribuidos con el proyecto y también recursos del JAR. Siete son válidos y quince contienen errores intencionales. El formato sigue [el contrato 07](../../../../../docs/07-formato-archivo-solicitudes.md).

La configuración de cada fila es necesaria para interpretar el resultado. Los comentarios del archivo no cambian N/P. El selector de FileSelectionDemo utiliza N = 2, P = 8; para 02-varios-elevadores usar el diagnóstico de consola con N = 3. Preparar un archivo no inicia la simulación.

| Archivo | N | P | Resultado esperado |
| --- | ---: | ---: | --- |
| [01-minimo.txt](01-minimo.txt) | 1 | 4 | Válido; 1 instrucciones. |
| [02-varios-elevadores.txt](02-varios-elevadores.txt) | 3 | 8 | Válido; 3 instrucciones. |
| [03-sentidos-mixtos.txt](03-sentidos-mixtos.txt) | 2 | 8 | Válido; 6 instrucciones. |
| [04-repetidos.txt](04-repetidos.txt) | 2 | 8 | Válido; 6 instrucciones. |
| [05-un-piso.txt](05-un-piso.txt) | 1 | 1 | Válido; 1 instrucciones. |
| [06-bom-crlf.txt](06-bom-crlf.txt) | 2 | 8 | Válido; 2 instrucciones. |
| [07-espacios-comentarios.txt](07-espacios-comentarios.txt) | 2 | 8 | Válido; 2 instrucciones. |
| [08-error-ultima-linea.txt](08-error-ultima-linea.txt) | 2 | 8 | HALL_UP_AT_TOP; línea 4. |
| [09-piso-invalido.txt](09-piso-invalido.txt) | 2 | 8 | FLOOR_OUT_OF_RANGE; línea 2. |
| [10-elevador-invalido.txt](10-elevador-invalido.txt) | 2 | 8 | ELEVATOR_OUT_OF_RANGE; línea 2. |
| [11-direccion-invalida.txt](11-direccion-invalida.txt) | 2 | 8 | INVALID_DIRECTION; línea 2. |
| [12-tab-instruccion.txt](12-tab-instruccion.txt) | 2 | 8 | INVALID_CHARACTER; línea 2. |
| [13-comentario-final.txt](13-comentario-final.txt) | 2 | 8 | INVALID_ARITY; línea 2. |
| [14-utf8-invalido.txt](14-utf8-invalido.txt) | 2 | 8 | INVALID_UTF8; alcance de archivo. |
| [15-vacio.txt](15-vacio.txt) | 2 | 8 | EMPTY_BATCH; alcance de archivo. |
| [16-solo-comentarios.txt](16-solo-comentarios.txt) | 2 | 8 | EMPTY_BATCH; alcance de archivo. |
| [17-bom-interior.txt](17-bom-interior.txt) | 2 | 8 | INVALID_BOM; línea 2. |
| [18-control-prohibido.txt](18-control-prohibido.txt) | 2 | 8 | UNKNOWN_INSTRUCTION; línea 2. |
| [19-varios-errores.txt](19-varios-errores.txt) | 2 | 8 | Rechazado; HALL_UP_AT_TOP en 2, ELEVATOR_OUT_OF_RANGE en 3, INVALID_DIRECTION en 4. |
| [20-cr-aislado.txt](20-cr-aislado.txt) | 2 | 8 | INVALID_LINE_ENDING; línea 1. |
| [21-desbordamiento.txt](21-desbordamiento.txt) | 2 | 8 | INTEGER_OUT_OF_RANGE; línea 2. |
| [22-sentido-extremo.txt](22-sentido-extremo.txt) | 2 | 8 | HALL_DOWN_AT_BOTTOM; línea 2. |

## Casos con bytes especiales

- 06-bom-crlf.txt: BOM UTF-8 real, terminadores CRLF y última línea sin salto. No es un texto que contenga las letras BOM.
- 12-tab-instruccion.txt: contiene un tab real, por eso se rechaza.
- 14-utf8-invalido.txt: contiene los bytes C3 28, que no forman UTF-8 válido. No reparar ni convertir su codificación.
- 15-vacio.txt: tiene exactamente cero bytes. Un comentario lo convertiría en otro caso, aunque también fuera lote vacío.
- 17-bom-interior.txt: U+FEFF dentro de un comentario, expresamente prohibido.
- 20-cr-aislado.txt: contiene un CR aislado; no sustituirlo por CRLF.

.gitattributes conserva los bytes de todos los .txt de esta carpeta; .editorconfig desactiva para estos datos la normalización automática heredada del código. Los casos 06, 14 y 20 se muestran como binarios en el diff para preservar su intención. No aplicar normalización masiva de terminadores/codificación a estos datos.

## Uso desde la raíz del proyecto

```powershell
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\src\main\resources\examples\requests\03-sentidos-mixtos.txt 2 8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestImportDemo .\src\main\resources\examples\requests\04-repetidos.txt 2 8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\src\main\resources\examples\requests\15-vacio.txt 2 8
$LASTEXITCODE
```

Los dos primeros diagnósticos deben devolver salida 0; el archivo vacío, salida 1 con EMPTY_BATCH. Los repetidos conservan seis entradas; la cantidad de servicios únicos depende de la deduplicación de trabajo activo. Los HALL y CABIN no representan automáticamente a una misma persona.

Para la prueba visual de cancelar y corregir una selección:

```powershell
java -cp .\target\classes com.elevatormanager.diagnostics.FileSelectionDemo
```

Cargar 01-minimo, cancelar otra selección y comprobar que sigue un lote de una solicitud. Abrir 15-vacio u 08-error-ultima-linea: el error se muestra y el lote anterior permanece. Después cargar 03-sentidos-mixtos y verificar seis solicitudes. Cerrar debe devolver el prompt. La ventana principal del JAR no se modifica por este diagnóstico.

## Integridad de los datos especiales

| Archivo | Bytes | SHA-256 |
| --- | ---: | --- |
| 06-bom-crlf.txt | 47 | `217806776dbc4ccc5f1bf48ccb317281c4a1f2a5ca59922476df7665ef938615` |
| 14-utf8-invalido.txt | 45 | `c801060eb68fdbfe515d0fd7e8dba139638795555635c7df190c3ec535f62c57` |
| 15-vacio.txt | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| 20-cr-aislado.txt | 19 | `a908fa1c1f16c401dd1cbd18c68698e8ad7f309643300256ab8f24cd4bce493a` |

No se distribuye un archivo con permisos bloqueados: los permisos del ZIP no son una prueba portable de ACL. Los tests inyectan la denegación en el punto de apertura del lector y verifican su recuperación, sin tocar permisos del usuario. La prueba de ACL real depende del sistema y se distingue de esa evidencia en [la validación T46](../../../../../docs/comprobaciones/T46-validacion-local.md).

# T44 — Implementar parser y validación de lote

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes.
- **Base:** contrato T43 1.0.0 entregado en el ZIP anterior; último commit confirmado por David `ae5a1bf` de T42, 458 pruebas Maven. No se ha comunicado un hash de incorporación de T43.
- **Estado:** cerrada. David confirmó 48 pruebas específicas y 506 acumuladas, sin fallos, errores u omisiones, BUILD SUCCESS y commit `9ae324d`. Esta ficha conserva el contenido de la entrega original T44.

## Resultado y alcance

RequestFileReader lee y valida todo el archivo sin crear trabajadores, mutar agendas ni acceder al controlador. Solo devuelve PreparedRequestBatch cuando no existe ningún error; si falla una línea, el resultado no contiene entradas parcialmente válidas. No recibe ni almacena un lote anterior: no puede sustituirlo accidentalmente. La sustitución atómica real en el controlador se implementará en T45.

Se aplica el contrato [07](../07-formato-archivo-solicitudes.md): UTF-8 estricto, BOM inicial opcional único, LF/CRLF mezclables, espacios ASCII, comentarios completos con #, sin tabs ni comentarios finales, argumentos exactos, límites y sentidos posibles. Se conservan orden, números de línea y repetidos, y se normaliza el valor numérico de ceros iniciales. No se ordena por piso ni se deduplica al parsear.

## API y datos

| Clase | Responsabilidad |
| --- | --- |
| RequestFileFormat | Versión y límites: 1 MiB, 10,000 líneas, 4,096 puntos de código por línea. |
| RequestEntry | Interfaz sellada con descriptores Hall y Cabin. Línea, piso, dirección o cabina; sin UUID, época, fecha ni comando. |
| PreparedRequestBatch | Configuración capturada, lista copiada de descriptores, cantidad física de líneas y bytes originales. |
| BatchDiagnostic | Código enum, línea opcional, mensaje, formato esperado y excepción original opcional. |
| BatchReadResult | Lote válido o lista inmutable de diagnósticos; rechaza construcciones ambiguas o vacías. |
| RequestFileReader | Lectura limitada, decodificación, análisis completo y validación de dominio. |
| RequestFileDemo | Comprobación por consola; no crea sesión ni mueve elevadores. |

Los constructores de descriptores exigen valores estructurales positivos. El lote verifica rangos del edificio, orden estricto de líneas y límites de metadatos, además de copiar su lista. Dos instrucciones iguales en líneas distintas se conservan. La validez de estos descriptores se contrasta en las pruebas con HallRequest y CabinRequest existentes; no se generan identidades temporales para validar en producción.

No cambian los fuentes anteriores de producción, la herencia de Request, los hilos del motor ni pom.xml. No se añaden librerías externas.

## Propiedad, concurrencia y límites de lectura

read(Path, config) abre un stream propio y lo cierra antes de producir el resultado, también ante exceso, interrupción o fallo. Una excepción de apertura, lectura o cierre genera READ_ERROR con su causa original. read(InputStream, config) toma un stream prestado desde su posición actual y no lo cierra, sea válido o inválido el contenido. El llamador conserva la propiedad y es responsable de cerrarlo.

La lectura no confía en Files.size ni en que el archivo deje de crecer: consume como máximo el límite más un byte y rechaza el exceso. Una lectura de bloque que devuelve cero usa lectura de un byte como alternativa, evitando un bucle vacío. La memoria está acotada por el contenido permitido; puede haber copias de bytes, texto y estructuras, por lo que 1 MiB no representa el máximo total de memoria de la operación.

parse(byte[], config) comprueba tamaño y toma una copia acotada antes de decodificar con CodingErrorAction.REPORT. No repara UTF-8 inválido, no convierte desde otra codificación y no acepta UTF-16 como alternativa. La ausencia de salto final no añade una línea ficticia. BOM y terminadores cuentan bytes, pero no aumentan la longitud Unicode de la línea; comentarios y vacías cuentan para el límite físico.

La clase no mantiene campos mutables ni ejecutores. Puede compartirse entre lecturas independientes concurrentes. No se garantiza seguridad al compartir el mismo InputStream mutable entre lectores ni una instantánea atómica de un archivo que otro proceso modifica durante la lectura. La validez corresponde a los bytes efectivamente leídos.

Las APIs son síncronas y deben usarse fuera del EDT; no dependen de Swing para imponer esa política. T45 proporcionará la ejecución de I/O y la revalidación del contexto del controlador. Se comprueba el flag de interrupción sin borrarlo; se devuelve READ_ERROR con InterruptedIOException. No puede garantizarse que una interrupción desbloquee cualquier read externo ya bloqueado.

## Diagnósticos y precedencia

Las líneas inválidas reciben un diagnóstico principal por línea en orden ascendente, sin añadir EMPTY_BATCH si ya hay causas de error. Se revisan BOM y caracteres, longitud Unicode, instrucción conocida, aridad y argumentos de izquierda a derecha. Dentro de cada argumento se comprueban sintaxis numérica, representación y rango antes de avanzar al siguiente; por eso CABIN 3 texto, con N = 2, informa ELEVATOR_OUT_OF_RANGE.

Los números se examinan primero como dígitos ASCII y se acumulan protegiendo Integer.MAX_VALUE; los ceros iniciales no producen un desbordamiento ficticio. El piso 0 y la cabina 0 producen sus códigos de rango, no un fallo inespecífico del constructor.

Un error general de tamaño, codificación, terminación de líneas o exceso de líneas puede detener el análisis según 07. La decodificación y los errores de lectura no inventan un número de línea; los terminadores inválidos y la línea 10,001 sí tienen posición física conocida. Los mensajes no incluyen líneas gigantes ni el archivo completo. Las causas de I/O/decodificación se conservan; null en argumentos de programación se rechaza con NullPointerException en lugar de convertirlo en un error de archivo.

## Validación en Windows

El ZIP contiene el proyecto fuente completo con pom.xml en la raíz, todos los .java y documentación Markdown. Excluye .git, target y registros generados. Se conserva el repositorio Git local. Comprobar cambios personales antes de sobrescribir.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T44-parser-y-validacion-lote.zip" -DestinationPath . -Force
mvn -Dtest=RequestFileTest test
mvn "-Dtest=RequestFileTest,ModelRulesTest,RequestTest,SimulationConfigTest" test
mvn clean verify
```

Se esperan 48 pruebas específicas, 72 seleccionadas y 506 acumuladas, sin fallos, errores ni omisiones y con BUILD SUCCESS. Son expectativas sobre la base validada de 458, no resultados Maven ejecutados en este entorno. Los bucles internos y verificaciones adicionales del CLI no cuentan como nuevos casos JUnit.

## Comprobación manual del lector

Este ejemplo crea un archivo temporal de trabajo bajo logs para comprobar el lector. Los archivos de ejemplo distribuidos y la integración del selector siguen en T46. No se agrega un botón de importar a la ventana mínima.

```powershell
New-Item -ItemType Directory -Force .\logs\validacion-t44 | Out-Null
@'
# Lote para dos cabinas y ocho pisos
HALL 3 UP
CABIN 1 8
HALL 8 DOWN
CABIN 2 1
'@ | Set-Content -LiteralPath .\logs\validacion-t44\lote-valido.txt -Encoding utf8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\logs\validacion-t44\lote-valido.txt 2 8
$LASTEXITCODE
@'
# Ninguna entrada se prepara si falla la última línea
HALL 3 UP
CABIN 1 8
HALL 8 UP
'@ | Set-Content -LiteralPath .\logs\validacion-t44\lote-invalido.txt -Encoding utf8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\logs\validacion-t44\lote-invalido.txt 2 8
$LASTEXITCODE
```

El primero debe mostrar cuatro instrucciones y salida 0; el segundo debe mostrar HALL_UP_AT_TOP en línea 4 y salida 1, sin lote parcial. Los bytes pueden diferir entre Windows PowerShell y PowerShell 7 por BOM/terminadores, ambos permitidos. Invocar el diagnóstico sin los tres argumentos devuelve uso y salida 2.

El diagnóstico utiliza tiempos de 1 ms solo para construir la configuración que exige el modelo: no intervienen en el formato ni inician una simulación. Ningún resultado del CLI confirma importación al controlador.

## Archivos de esta tarjeta y commit sugerido

Nueve fuentes Java nuevos, tres fichas T44 y cuatro documentos de estado/arquitectura/contrato/pruebas actualizados. No se ha recibido un commit de T43: si esa entrega sigue sin incorporarse en el equipo local, conservar también sus documentos al preparar el commit correspondiente; no se inventa un hash ni se modifica el historial Git.

Después de validar, actualizar la casilla T44 y el estado del plan con las cifras reales. Incorporar los archivos de esta tarjeta:

```powershell
$archivosT44 = @(
    'src/main/java/com/elevatormanager/io/RequestFileFormat.java',
    'src/main/java/com/elevatormanager/io/RequestEntry.java',
    'src/main/java/com/elevatormanager/io/PreparedRequestBatch.java',
    'src/main/java/com/elevatormanager/io/BatchDiagnostic.java',
    'src/main/java/com/elevatormanager/io/BatchReadResult.java',
    'src/main/java/com/elevatormanager/io/RequestFileReader.java',
    'src/main/java/com/elevatormanager/diagnostics/RequestFileDemo.java',
    'src/test/java/com/elevatormanager/io/RequestFileScenarios.java',
    'src/test/java/com/elevatormanager/io/RequestFileTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/03-arquitectura.md',
    'docs/06-pruebas.md',
    'docs/07-formato-archivo-solicitudes.md',
    'docs/implementacion/T44-parser-y-validacion-lote.md',
    'docs/implementacion/T44-codigo-completo.md',
    'docs/comprobaciones/T44-validacion-local.md'
)
git diff --check
git add -- $archivosT44
git diff --cached --check
git diff --cached --stat
git commit -m "T44: implementar parser y validacion de lote"
git status --short
```

No se crea un commit automáticamente en esta entrega. T45 conectará la lectura al controlador, revalidará RESET/sesión/configuración/época, confirmará el reemplazo y asignará identidades antes de liberar el lote una vez al iniciar.

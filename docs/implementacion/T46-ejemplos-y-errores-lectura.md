# T46 — Crear ejemplos y probar errores de lectura

> Actualización al iniciar T47: David confirmó el cierre de las fases 1–7. La base adjunta contiene el commit `84f6261` de T46; se recuperó evidencia de 578 pruebas con BUILD SUCCESS. El contenido siguiente conserva el informe original previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes; última tarjeta de la fase.
- **Base:** T45 validada por David, commit `4f99454`, 40 pruebas específicas y 546 acumuladas, BUILD SUCCESS.
- **Estado:** implementada y comprobada localmente; pendiente de Maven y revisión visual en el equipo de David. La fase 7 todavía no se declara cerrada.

## Resultado

Se distribuyen 22 archivos en src/main/resources/examples/requests, con [catálogo de configuración y resultados](../../src/main/resources/examples/requests/README.md). Incluyen mínimo, tres elevadores, sentidos mixtos, repetidos, un piso, BOM/CRLF, comentarios/espacios y quince errores intencionales. Se conserva el contrato 1.0.0 sin añadir campos ni instrucciones.

Los ejemplos se cargan por classloader en las pruebas y se incluyen como recursos del JAR mediante la configuración existente de Maven, sin modificar pom.xml. No existen dos copias que puedan divergir. Las pruebas de integración crean copias temporales para usar importRequests(Path), y las eliminan al terminar.

| Colección | Cantidad | Verificación |
| --- | ---: | --- |
| Ejemplos válidos | 7 | Configuración N/P documentada, número de entradas y servicio con actores reales. |
| Ejemplos inválidos | 15 | Código y línea esperados; sin lote parcial ni reemplazo del preparado anterior. |
| Escenarios nuevos | 32 | 12 de lectura/recursos, 12 de selección/EDT y 8 de integración/recuperación. |

El archivo vacío es de cero bytes. El tab, BOM interior, UTF-8 incorrecto y CR aislado son bytes/caracteres reales. .gitattributes conserva todos los .txt y muestra como binarios los casos 06, 14 y 20; .editorconfig excluye estos datos de la normalización de codificación, terminadores, indentación y salto final; no se deben normalizar sus terminadores ni corregir la codificación inválida.

## Selección y cancelación

RequestImportHandler encapsula la selección en EDT y delega la lectura al flujo asíncrono de T45. Su fábrica forDialog usa JFileChooser; la alternativa con Supplier permite probar las decisiones sin abrir diálogos nativos. La ventana principal no cambia: la conexión de sus controles completos corresponde a la fase 8.

| Resultado del manejador | Significado |
| --- | --- |
| CANCELLED | El usuario canceló el selector. No se llama al lector ni a cancelImport; conserva lote, generación y otras lecturas activas. |
| NOT_ALLOWED | Ya hay un selector abierto o el estado no es RESET. No se abre otro diálogo ni se lee un archivo. |
| FINISHED | Llegó un recibo vigente del controlador; contiene IMPORTED, INVALID o REJECTED según T45. |
| STALE | La sesión, estado, época o generación cambió antes de aplicar el recibo en EDT; no tratarlo como lote preparado actual. |

Aprobar un diálogo no autoriza la importación por sí solo. El controlador vuelve a comprobar RESET y el contexto. El callback de resultado y el de fallo se ejecutan en EDT; las excepciones del propio selector se separan de los diagnósticos del archivo. Una selección nula es un fallo del proveedor de selección, no una cancelación silenciosa.

El filtro de archivos de texto es una ayuda del diálogo: mantiene disponible la opción de otras extensiones, porque el contrato valida contenido y no nombre. El manejador no crea hilos ni hace join/get sobre la importación; tampoco edita el lote directamente.

La prueba de respuesta diferida retiene únicamente su entrega, sin bloquear el EDT. Tras Start y reset, aplica esa entrega en EDT y comprueba STALE. FileSelectionDemo no muestra diagnósticos antiguos como si fueran actuales y consulta preparedBatch para presentar la cantidad vigente.

## Errores de lectura y recuperación

RequestFileReader conserva su API pública y sigue abriendo con Files.newInputStream por defecto. Un InputOpener final, accesible solo al paquete, permite inyectar fallos en el punto de apertura para comprobar la lógica real del lector y sus recursos sin cambiar permisos del equipo. Las lecturas siguen acotadas, con UTF-8 estricto y fuera del EDT en el controlador.

| Situación | Comportamiento comprobado |
| --- | --- |
| Archivo inexistente o desaparecido antes de abrir | READ_ERROR con causa original; conservar lote y permitir otra importación. |
| Archivo vacío o solo comentarios | EMPTY_BATCH; no significa vaciar el lote preparado. |
| Acceso denegado al abrir | READ_ERROR con AccessDeniedException original; no confirmar ni mutar el lote. |
| SecurityException de apertura | READ_ERROR conserva la causa; no se sustituye por un éxito vacío. |
| Lectura que falla tras un prefijo válido | No exponer ese prefijo como lote; cerrar recurso propio y conservar anterior. |
| Error de cierre | Rechazar la lectura, conservar causa y liberar los demás recursos. |
| Error de lectura más error al cerrar | Mantener la causa primaria y la excepción de cierre suprimida. |
| Exceso del límite de bytes | Rechazar y cerrar el stream propio. |
| Archivo corregido o acceso restablecido | El mismo lector/controlador permite volver a importar y ejecutar. |
| Cancelar selector mientras otra lectura sigue activa | Cancelar solo el diálogo; la lectura anterior sigue vigente. |

No se usa Files.isReadable como autorización previa: un archivo puede cambiar entre consultar metadatos y abrirlo. Se prueba además la desaparición real del archivo antes de la apertura. Las causas del lector se mantienen en BatchImportResult y los errores de usuario no convierten la sesión en fallida.

Las pruebas de acceso denegado son reproducibles mediante inyección de AccessDeniedException tanto en el lector como en su integración con el controlador. No se afirma que se hayan validado ACL reales de Windows/POSIX: el entorno local tiene privilegios elevados y bloqueó la tentativa de iniciar un proceso sin privilegios. No se modificaron permisos del proyecto y no se incluye un archivo supuestamente restringido en el ZIP, porque eso no acredita la denegación efectiva en otro equipo.

## Diagnóstico gráfico separado

FileSelectionDemo compone un controlador fijo de dos elevadores y ocho pisos, el selector real y WindowCloseHandler. Permite cargar, cancelar, corregir errores y cerrar con confirmación de recursos. No incluye Start, de modo que mantiene RESET y no mueve cabinas. No sustituye ElevatorManager ni adelanta el formulario de configuración de T47.

El cierre por Cerrar o X deshabilita acciones y espera el futuro de cierre antes de disponer la ventana. El área de mensajes muestra la causa legible y la cantidad actual del lote. El diagnóstico requiere un escritorio gráfico y rechaza un entorno headless antes de crear la sesión.

## Validación en Windows

El ZIP contiene el proyecto fuente completo con pom.xml en la raíz, todos los .java, los 22 .txt y documentación Markdown. Excluye .git, target y registros generados. Conservar cambios personales y el repositorio local antes de extraer.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T46-ejemplos-y-errores-lectura.zip" -DestinationPath . -Force
mvn "-Dtest=ReadFailureTest,FileSelectionTest,ImportReadErrorTest" test
mvn "-Dtest=ReadFailureTest,FileSelectionTest,ImportReadErrorTest,RequestFileTest,RequestImportTest" test
mvn clean verify
```

Se esperan 32 específicas, 120 seleccionadas y 578 acumuladas, sin fallos, errores ni omisiones y con BUILD SUCCESS. Son expectativas sobre la base confirmada de 546; no se ejecutó Maven aquí. Los avisos de error provocados por las pruebas de logging siguen siendo intencionales.

Para probar archivos distribuidos, sin crear otros ejemplos a mano:

```powershell
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\src\main\resources\examples\requests\03-sentidos-mixtos.txt 2 8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestImportDemo .\src\main\resources\examples\requests\04-repetidos.txt 2 8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestFileDemo .\src\main\resources\examples\requests\15-vacio.txt 2 8
$LASTEXITCODE
java -cp .\target\classes com.elevatormanager.diagnostics.FileSelectionDemo
```

Los dos primeros deben terminar con salida 0. El vacío devuelve salida 1 con EMPTY_BATCH. En el diagnóstico gráfico:

1. Cargar 01-minimo.txt: queda una solicitud preparada, sin movimiento.
2. Abrir de nuevo y cancelar: sigue una solicitud; no se reemplaza ni se genera un error de lectura.
3. Elegir 15-vacio.txt y después 08-error-ultima-linea.txt: mostrar sus errores y conservar la solicitud anterior.
4. Elegir 03-sentidos-mixtos.txt: reemplazar por seis solicitudes.
5. Cerrar y comprobar retorno al prompt. Puede repetir con la X.

La configuración del diagnóstico es N = 2, P = 8. El archivo 02-varios-elevadores necesita N = 3 en la consola; seleccionarlo en este diagnóstico debe rechazar la cabina 3 y conservar el lote anterior. Las pruebas headless verifican el manejador mediante decisiones inyectadas, no el aspecto visual del diálogo nativo; confirmar esa revisión en el equipo local.

## Archivos y commit sugerido

Los nueve fuentes nuevos o modificados se reproducen en [código completo](T46-codigo-completo.md). La entrega contiene además los datos del catálogo, sus reglas de Git y la actualización de documentación. Se registra el cierre confirmado de T45. T43 se marca como contrato adoptado para T44, sin atribuirle un hash individual no comunicado.

Después de validar Maven y la revisión visual, marcar T46 y registrar el cierre de fase 7 en el plan. Incorporar los 45 archivos de esta tarjeta:

```powershell
$archivosT46 = @(
    '.gitattributes',
    '.editorconfig',
    'src/main/resources/examples/requests',
    'src/main/java/com/elevatormanager/io/RequestFileReader.java',
    'src/main/java/com/elevatormanager/ui/RequestImportHandler.java',
    'src/main/java/com/elevatormanager/diagnostics/FileSelectionDemo.java',
    'src/test/java/com/elevatormanager/io/ReadFailureScenarios.java',
    'src/test/java/com/elevatormanager/io/ReadFailureTest.java',
    'src/test/java/com/elevatormanager/ui/FileSelectionScenarios.java',
    'src/test/java/com/elevatormanager/ui/FileSelectionTest.java',
    'src/test/java/com/elevatormanager/simulation/ImportReadErrorScenarios.java',
    'src/test/java/com/elevatormanager/simulation/ImportReadErrorTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/03-arquitectura.md',
    'docs/06-pruebas.md',
    'docs/07-formato-archivo-solicitudes.md',
    'docs/implementacion/T43-formato-archivo-solicitudes.md',
    'docs/comprobaciones/T43-validacion-documental.md',
    'docs/implementacion/T45-importacion-limitada-reset.md',
    'docs/comprobaciones/T45-validacion-local.md',
    'docs/implementacion/T46-ejemplos-y-errores-lectura.md',
    'docs/implementacion/T46-codigo-completo.md',
    'docs/comprobaciones/T46-validacion-local.md'
)
git diff --check
git add -- $archivosT46
git diff --cached --check
git diff --cached --stat
git commit -m "T46: crear ejemplos y probar errores de lectura"
git status --short
```

No se crea un commit automáticamente en esta entrega. La siguiente tarjeta es T47: formulario de configuración, inicio de la fase 8.

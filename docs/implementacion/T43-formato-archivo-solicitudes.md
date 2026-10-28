# T43 — Definir y documentar el formato del archivo de solicitudes

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes.
- **Base:** T42 validada por David en `ae5a1bf`: 24 pruebas específicas, 458 acumuladas, BUILD SUCCESS y EventLoggingDemo terminado correctamente.
- **Estado:** contrato definido y comprobado documentalmente; pendiente de revisión e incorporación. No se marca T43 completada antes de esa confirmación.

## Entrega y alcance

[07 — Formato del archivo de solicitudes](../07-formato-archivo-solicitudes.md) define el contrato 1.0.0 que implementará T44. Conserva las decisiones previas de UTF-8, HALL/CABIN, comentarios completos con # y sustitución atómica del lote, y concreta los casos que aún admitían interpretaciones distintas.

| Aspecto | Decisión |
| --- | --- |
| Formas de instrucción | HALL piso UP/DOWN y CABIN id piso, con tres tokens exactos. |
| Codificación | UTF-8 estricto, BOM único opcional en el byte 0, LF/CRLF y última línea sin salto permitida. |
| Separadores | Espacio ASCII; se admiten varios y espacios exteriores. Tabs y comentarios finales no se admiten. |
| Números | Dígitos ASCII sin signo, ceros iniciales permitidos, sin desbordamiento y con rango del edificio. |
| Sentidos | UP desde pisos inferiores a P; DOWN desde pisos superiores a 1. |
| Límites | 1 MiB, 10,000 líneas físicas y 4,096 puntos de código por línea, incluidos comentarios/vacías donde corresponda. |
| Identidad | Descriptores al parsear; nuevos UUID y época al confirmar el lote; comandos al enviarlos por el flujo normal. |
| Orden | Se conserva el orden de presentación al controlador; no se promete el de aceptación o servicio entre actores. |
| Repetidos | Se conservan en el lote. El motor unifica solo equivalentes activos según D18. |
| Atomicidad | Cualquier error rechaza el archivo completo y conserva el lote anterior; importar no mueve cabinas. |
| Confirmación | Únicamente RESET y misma sesión, configuración y época; revalidación al terminar la lectura. |
| Diagnósticos | Código, línea física y causa cuando hay una posición fiable; errores generales con alcance de archivo. |

Se incluyen cinco archivos completos como bloques Markdown válidos, 26 ejemplos de líneas inválidas, un archivo completo con error en su última línea y una matriz de pruebas futuras. No se crean todavía archivos .txt de muestra ni un parser de producción: corresponden a T44 y T46. No se añade calendarización ni se interpretan líneas HALL/CABIN como trayectos vinculados de pasajeros.

## Compatibilidad

La sección 7 de 05 ahora apunta al contrato completo de 07. La gramática V1 de mensajes entre actores no cambia ni se afirma que su codec esté implementado. Se actualizan D22, arquitectura y plan de pruebas para que no compitan dos definiciones de importación. El Kanban incorpora la confirmación real de T42 y deja T43 lista para revisión.

No se modifican Java, POM, herencia ni hilos. El ZIP incluye el proyecto fuente completo de T42 con estas actualizaciones documentales. Se compararon los fuentes y pom.xml byte a byte con el ZIP T42; no se añadieron ni eliminaron clases. La base Maven confirmada sigue siendo 458 pruebas; no hubo una nueva ejecución Maven ni pruebas Java añadidas por esta tarjeta documental.

## Archivos nuevos o actualizados

| Archivo | Cambio |
| --- | --- |
| docs/07-formato-archivo-solicitudes.md | Contrato normativo, ejemplos y diagnósticos. |
| docs/02-decisiones.md | D22 apunta a 07; precisión de D18, límites y léxico. |
| docs/03-arquitectura.md | Flujo de presentación, preparación y confirmación de lote. |
| docs/05-protocolo.md | Sección 7 delega el contrato de archivo a 07; mensajes V1 sin cambios. |
| docs/06-pruebas.md | Matriz futura de bordes para T44–T46. |
| docs/01-analisis-y-plan.md | T42 confirmada y avance documental T43. |
| docs/implementacion/T42-eventos-obligatorios-y-errores.md | Estado confirmado de T42 en ae5a1bf. |
| docs/comprobaciones/T42-validacion-local.md | Evidencia de Maven y diagnóstico recibida de David. |
| docs/implementacion/T43-formato-archivo-solicitudes.md | Esta ficha e instrucciones de incorporación. |
| docs/comprobaciones/T43-validacion-documental.md | Comprobaciones de consistencia y alcance. |

## Revisión e incorporación en Windows

El ZIP contiene pom.xml en la raíz, todos los fuentes y la documentación. Excluye .git, target y logs generados. Antes de extraer, comprobar que no hay cambios personales que se sobrescribirían. Se conserva el repositorio Git local.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T43-formato-archivo-solicitudes.zip" -DestinationPath . -Force
git diff --check
git diff --stat
git status --short
code .\docs\07-formato-archivo-solicitudes.md
```

Revisar especialmente las secciones 2, 6, 7 y 8 de 07: léxico/límites, duplicados, atomicidad y diagnósticos. La revisión de T43 es documental; aún no existe un comando para importar estos ejemplos. No es necesario repetir Maven por cambios exclusivamente documentales. Si se desea comprobar voluntariamente la base, mvn clean verify debe conservar las 458 pruebas; no se atribuye esa ejecución a esta entrega.

Cuando se confirme la revisión, marcar T43 y actualizar el estado del plan. Commit sugerido con los diez archivos de la tarjeta:

```powershell
$archivosT43 = @(
    'docs/07-formato-archivo-solicitudes.md',
    'docs/01-analisis-y-plan.md',
    'docs/02-decisiones.md',
    'docs/03-arquitectura.md',
    'docs/05-protocolo.md',
    'docs/06-pruebas.md',
    'docs/implementacion/T42-eventos-obligatorios-y-errores.md',
    'docs/comprobaciones/T42-validacion-local.md',
    'docs/implementacion/T43-formato-archivo-solicitudes.md',
    'docs/comprobaciones/T43-validacion-documental.md'
)
git add -- $archivosT43
git diff --cached --check
git diff --cached --stat
git commit -m "T43: definir formato del archivo de solicitudes"
git status --short
```

No se crea un commit automáticamente en esta entrega. Después de confirmar T43, corresponde T44: implementar el parser y la validación completa del lote conforme a 07.

# MARJHO Print Service 1.3.0

Complemento Android independiente basado en `android.printservice.PrintService`.
Su objetivo es aparecer en el selector de impresión del sistema Android y recibir documentos PDF de otras aplicaciones.

## Alcance actual

- APK independiente con identificador `com.marjho.printservice` y servicio Android `PrintService`.
- Descubrimiento DNS-SD/mDNS automático y consulta IPP/IPPS para determinar formatos y capacidades anunciados.
- Envío de PDF por IPP solo cuando `application/pdf` aparece entre los formatos admitidos.
- Flujo ESC/P-R existente para Epson L4150 configurada de forma explícita; no se infiere por el puerto TCP.
- Actividad independiente **PDF View** para abrir PDF desde «Abrir con», pasar páginas con gestos, compartir y enviar directamente a la impresora configurada.
- Pantalla PDF con selector de documentos y lista privada de archivos recientes.
- OCR local de documentos escaneados con ML Kit; el PDF se procesa en el teléfono.
- Editor PDF con vista por página, giro, eliminación de páginas, marca de agua y extracción de texto; editor DOCX básico y texto plano; conversión texto-PDF e imagen.
- Exportación mediante el selector de documentos Android; no requiere nube ni Firebase.
- Límite conservado del controlador ESC/P-R: hasta 20 páginas A4 por trabajo.
- Interfaz dividida en cuatro pantallas: Red, Archivos, PDF y Ajustes. La IP manual y las opciones de Epson se abren como diálogos.

El detalle actualizado y los límites de cada formato están en [`docs/ETAPA_4_AUTODETECCION_Y_EDITORES.md`](docs/ETAPA_4_AUTODETECCION_Y_EDITORES.md). La etapa 3 conserva el historial de la primera integración.

## Importante: Epson L4150

La documentación oficial de Epson para L4150 especifica USB, Wi-Fi 802.11 b/g/n y Wi-Fi Direct. No especifica conector Ethernet RJ-45. Puede estar en la misma LAN que equipos conectados al router por cable Ethernet, pero la propia impresora se conecta por Wi-Fi. Para conexión Ethernet física directa hace falta otro modelo o un servidor/puente de impresión compatible.

La documentación de funciones de red consultada no marca IPP convencional como compatible para esta serie. MARJHO conserva ESC/P-R sobre TCP 9100 para la L4150; para otros modelos consulta IPP/IPPS y solo envía PDF si la propia impresora anuncia `application/pdf`. La respuesta de TCP 9100 por sí sola no confirma el lenguaje ni la impresión física.

Fuentes oficiales consultadas:
- Epson Argentina L4150: https://epson.com.ar/Para-el-hogar/Impresoras/Inyecci%C3%B3n-de-tinta/Impresora-Multifuncional-Epson-EcoTank-L4150/p/C11CG25303
- Manual de usuario L4150: https://files.support.epson.com/docid/cpd5/cpd54034.pdf
- Lista de funciones de red L4150: https://download4.epson.biz/sec_pubs/l4150_series/useg/en/manual.pdf

## Compilar

Requisitos:
- Android Studio reciente.
- JDK 21.
- Android SDK Platform 36.
- Conexión inicial para descargar Gradle y dependencias.

Abrir esta carpeta como proyecto Gradle y ejecutar:

```bash
./gradlew :app:assembleDebug
```

APK esperado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

La versión 1.3.0 se compiló en Android Studio y se instaló en el Xiaomi conectado. Se verificó en el dispositivo la actividad **PDF View** abriendo un PDF por `ACTION_VIEW` con URI autorizado, la vista de dos páginas, el gesto para avanzar página, la reaparición de controles y el panel del sistema para compartir. La ruta ESC/P-R de una página de prueba ya se imprimió físicamente en la Epson L4150 y el usuario confirmó la salida; la acción de imprimir desde el visor usa el envío directo IPP/ESC/P-R, aunque no se repitió una impresión física desde ese botón en esta iteración. El OCR compila integrado; queda verificar el texto extraído con un escaneo representativo antes de distribuir un APK.

## Instalar y configurar

1. Compilar e instalar `app-debug.apk` en Android.
2. Abrir MARJHO Print Service y esperar la búsqueda local automática.
3. Elegir la impresora detectada y revisar la ruta y capacidades que anunció.
4. Para Epson L4150 sin anuncio IPP utilizable, ingresar la IP y seleccionar explícitamente el perfil Epson L4150.
5. Abrir «Ajustes de impresión de Android» y habilitar MARJHO Print Service.
6. Desde otra aplicación, imprimir y seleccionar la impresora de red detectada por MARJHO.

El teléfono y la impresora deben poder comunicarse en la red local. En Wi-Fi Direct, conectar el teléfono al SSID Wi-Fi Direct de la impresora y usar la IP que informe la impresora para ese modo.

## Limitaciones de esta versión

- DNS-SD solo encuentra equipos que anuncian esos servicios; la IP manual sigue como respaldo.
- LPR y RAW se reconocen, pero no se envían sin un controlador compatible identificado.
- La ficha de Android usa el papel, color, dúplex y resolución que publica IPP. Si la impresora no los publica, Android necesita un valor básico de compatibilidad.
- El flujo TCP 9100/ESC-P-R debe probarse con el firmware Epson concreto. El perfil manual confirma que el usuario seleccionó la L4150, no valida la salida física.
- El servicio recibe PDF. Aplicaciones que no utilicen el framework de impresión Android no aparecerán automáticamente.
- Ethernet físico no es posible en la L4150 sin hardware adicional porque el modelo no tiene puerto RJ-45.
- La cancelación de un trabajo durante la transmisión no garantiza que la impresora pueda retirar páginas ya recibidas.
- OCR extrae texto de páginas escaneadas, pero no crea una capa buscable dentro del PDF; el texto se exporta por separado.
- DOCX permite cambiar texto de párrafos y guardar una copia del paquete, pero exige conservar la cantidad de párrafos y no ofrece maquetación fiel, encabezados/pies ni edición visual de tablas.
- Los archivos de texto editables admiten hasta 2 MB y se exportan como UTF-8. PDF/DOCX e imágenes tienen límite de importación de 40 MB.
- La app reconoce extensiones comunes de texto/código, aunque no aplica resaltado de sintaxis; RTF/HTML se manejan como texto fuente y no se renderizan.
- La lectura de formatos de imagen menos comunes depende de los códecs disponibles en Android.

## Arquitectura

```text
Aplicación Android
      |
      v
Android Print Framework
      |
      v
MarjhoPrintService (PrintService)
      |-- PrinterDiscoverySession
      |-- DNS-SD + IPP/IPPS y capacidades
      |-- cola/cancelación de PrintJob
      |-- copia segura del PDF temporal
      |
      +--> IPP Print-Job --> impresora que admite PDF
      |
      +--> ImpresorEscprDirecto --> TCP :9100 --> Epson L4150
```

## Archivos principales

- `app/src/main/java/com/marjho/printservice/MarjhoPrintService.kt`: servicio de impresión Android.
- `app/src/main/java/com/marjho/printservice/MainActivity.kt`: configuración y prueba de conexión.
- `app/src/main/java/com/marjho/printservice/network/BuscadorImpresorasRed.kt`: descubrimiento DNS-SD/mDNS.
- `app/src/main/java/com/marjho/printservice/network/ProtocoloImpresion.kt`: consulta IPP, selección de ruta y envío PDF.
- `app/src/main/java/com/marjho/printservice/documents/GestorDocumentos.kt`: PDF, DOCX, texto e imagen.
- `app/src/main/java/com/marjho/printservice/driver/ImpresorEscprDirecto.kt`: controlador existente extraído de MARJHO.
- `app/src/main/java/com/marjho/printservice/driver/EscprRasterCodec.kt`: codificador raster existente extraído de MARJHO.
- `app/src/main/java/com/marjho/printservice/driver/RleEscpr.kt`: compresión RLE requerida por el codificador raster.
- `app/src/main/AndroidManifest.xml`: registro del servicio del sistema.

## Licencia y atribución

Los archivos ESC/P-R reutilizados conservan sus comentarios originales. El codificador indica que toma como referencia el proyecto abierto `python-epson` (Jason S. McMullan, MIT, 2016), y aclara que no es un SDK oficial de Epson. Antes de distribuir comercialmente el APK, revisar el texto de licencia y las obligaciones de atribución del código de referencia original.

Las dependencias de impresión, PDF y OCR se documentan en [`docs/ETAPA_4_AUTODETECCION_Y_EDITORES.md`](docs/ETAPA_4_AUTODETECCION_Y_EDITORES.md), con sus licencias y límites.

## Etapa 3 — Red local y documentos (primera integración)

Se reorganizó la pantalla en secciones y se añadieron:
- Descubrimiento DNS-SD/mDNS de servicios `_ipp._tcp`, `_ipps._tcp`, `_printer._tcp` y `_pdl-datastream._tcp`.
- Ventana emergente de resultados; al tocar un resultado se guarda su IP como destino activo.
- IP manual, prueba TCP 9100 y envío directo ESC/P-R para PDF A4.
- Importación mediante Storage Access Framework de PDF, DOCX, TXT e imágenes.
- Vista previa de primera página PDF.
- Edición de texto plano TXT.
- Extracción del texto visible de DOCX para editarlo como borrador y guardarlo como TXT.
- Edición básica de imagen: rotar 90 grados y guardar PNG.
- Conversión de imagen a PDF A4 para imprimir.
- El editor de etiquetas de esta etapa histórica se retiró en la versión 1.3; el PrintService Android se conserva.

### Límites explícitos de esta etapa
- La extracción DOCX no reescribe el DOCX original ni conserva tablas, estilos, encabezados o imágenes; se exporta el texto editado como TXT.
- El PDF se visualiza y se puede imprimir; no se modifica el texto original del PDF ni se anotan/reestructuran páginas en esta etapa.
- El editor de imagen de esta etapa solo rota; no incluye todavía recorte, capas, filtros o borrador.
- La búsqueda mDNS solo detecta servicios anunciados. Una impresora encontrada no implica que acepte ESC/P-R. El envío directo ESC/P-R sigue siendo específico del flujo validado para Epson L4150 y debe probarse en hardware.
- El envío directo conserva el límite del controlador de 20 páginas por trabajo.
- Para DOCX/TXT y PDF que no estén en un formato compatible con el controlador, utilizar una aplicación externa para convertirlos a PDF y luego abrir ese PDF en MARJHO.
- La selección de un servicio IPP no activa automáticamente IPP: la ficha encontrada solo identifica el anuncio DNS-SD. La negociación IPP y la consulta de capacidades se implementarán como módulo separado, evitando enviar comandos incorrectos.

### Compilación
Abrir la carpeta `MARJHO_Print_Service` en Android Studio con JDK 21 y Android SDK 36. Sincronizar Gradle y ejecutar `:app:assembleDebug`.

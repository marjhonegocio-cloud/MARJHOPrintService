# Etapa 4 — detección de red y editores

Esta etapa amplía el servicio Android, la selección de impresoras y las herramientas de documentos. La búsqueda usa DNS-SD/mDNS; la compatibilidad de impresión se confirma consultando el protocolo anunciado. Abrir una conexión TCP no basta para elegir un lenguaje de impresora.

## Organización de la interfaz

La pantalla principal se distribuye en cuatro destinos inferiores: **Red**, **Archivos**, **PDF** y **Ajustes**. La sección PDF contiene el selector y la lista de documentos recientes; Archivos mantiene las herramientas de texto, Word e imagen. Configurar una IP y cambiar opciones de la Epson son diálogos puntuales. El editor de etiquetas y sus archivos se retiraron en la versión 1.3.

En teléfonos, la navegación inferior sigue el patrón de Material 3 para un máximo de cinco destinos compactos. Los diálogos se reservan para decisiones/configuración breves; el contenido de cada editor queda en su propia pantalla desplazable. La librería Material 3 Adaptive ofrece navegación para ventanas grandes, pero esta iteración está enfocada en teléfono; una adaptación a tablet o ventana ancha debería usar carril de navegación y paneles según el ancho disponible.

Referencias de diseño Android: [Material 3 para Compose](https://developer.android.com/develop/ui/compose/designsystems/material3), [Material 3 Adaptive](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive) y [diálogos Compose](https://developer.android.com/develop/ui/compose/components/dialog). OCR: [ML Kit Text Recognition v2 para Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

## Detección y envío de trabajos

- La app busca impresoras automáticamente al abrirse y vuelve a buscarlas desde «Buscar impresoras».
- El servicio Android también realiza DNS-SD cuando el usuario abre la lista del sistema.
- Se consultan anuncios `_ipp._tcp`, `_ipps._tcp`, `_printer._tcp` (LPR) y `_pdl-datastream._tcp` (RAW/AppSocket).
- Para IPP/IPPS se lee la ruta `rp` del anuncio TXT y se negocia IPP 2.0, con IPP 1.1 como compatibilidad alternativa.
- La ficha detectada muestra el modelo, los formatos aceptados, papel, color, dúplex, resoluciones anunciadas y si el equipo acepta trabajos.
- PDF se envía directamente por IPP/IPPS solo cuando la impresora anuncia `application/pdf`.
- ESC/P-R por TCP 9100 queda reservado al perfil Epson L4150 identificado o seleccionado manualmente. Que el puerto 9100 responda no identifica ESC/P-R.
- Impresoras que anuncian LPR o RAW sin un controlador conocido se dejan para el selector de impresión Android. No se les envía un flujo de otra marca.
- La ficha dinámica del servicio Android publica las capacidades IPP que se pudieron leer. Cuando la impresora no las anuncia, A4/300 dpi es una opción de compatibilidad de Android, no una medición del hardware.
- Android requiere que la persona habilite MARJHO Print Service en Ajustes de impresión para recibir trabajos desde otras aplicaciones. La app muestra el estado y abre esos ajustes; Android no permite activarlo silenciosamente.
- La impresión directa desde MARJHO puede usar IPP o el perfil ESC/P-R sin que el PrintService de Android esté habilitado. En una Epson L4150 se puede elegir ESC/P-R y enviar una página de prueba desde la ficha de impresora.

### Rutas de impresión

| Equipo detectado | Ruta directa | Comportamiento |
| --- | --- | --- |
| IPP/IPPS con PDF | Print-Job IPP con `application/pdf` | Directo desde la app o Android PrintService |
| Epson L4150 configurada | Raster ESC/P-R por TCP 9100, seleccionable desde la app | Límite de 20 páginas A4; el cierre TCP confirma transmisión, no salida física |
| LPR, RAW o IPP sin PDF | Sin controlador directo confirmado | Abrir el selector de impresión Android o el complemento del fabricante |

## PDF View, documentos, Word y texto

- Android registra una actividad con nombre **PDF View** para `ACTION_VIEW` y MIME `application/pdf`. Desde un gestor de archivos se puede elegir «Abrir con → PDF View»; esa actividad muestra solo el PDF y no inicia la pantalla principal.
- El visor muestra una página por vez; los gestos horizontales avanzan o retroceden. Dos botones flotantes permiten compartir el archivo y enviarlo directamente al destino IPP/PDF o Epson L4150 guardado. Los controles se atenúan solos y aparecen al tocar la pantalla.
- La impresión desde PDF View usa IPP o ESC/P-R directamente y no abre el selector/diálogo Android. Si no hay un destino directo compatible guardado, indica que hay que configurarlo en Red.
- La pantalla PDF ofrece el selector para PDF, Word, texto e imagen y una lista privada de hasta 12 documentos recientes, con un máximo de 160 MB en el almacenamiento interno de la app.
- En Archivos, PDF conserva herramientas de trabajo: muestra una página a la vez, permite avanzar/retroceder, girar o eliminar la página actual, aplicar marca de agua a todas las páginas y exportar una copia.
- La eliminación y el giro modifican una copia de trabajo en caché. El archivo elegido en el selector no se sobrescribe; la persona elige dónde guardar la copia.
- Se extrae texto seleccionable de PDF para exportar a TXT o crear un PDF nuevo de texto corrido. El botón **Extraer texto con OCR** también renderiza y reconoce página por página documentos escaneados.
- El OCR usa el modelo latino de ML Kit Text Recognition, incluido en la app. Funciona en el dispositivo, reconoce alfabetos latinos como español y no envía el documento a un servicio web; añade cerca de 4 MB al APK. El resultado es texto editable para exportar, no una capa buscable añadida al PDF original.
- DOCX: el editor modifica texto de párrafos en `word/document.xml` (también los párrafos dentro de celdas de tablas) y escribe otra copia conservando los demás archivos del paquete, como imágenes y relaciones. La edición DOCX exige la misma cantidad de párrafos. El texto se reparte entre los segmentos de texto que ya tenía cada párrafo; no hay vista de página fiel a Word ni edición de encabezados, pies, notas, campos o paginación. Para conservar el aspecto original, usar una aplicación de oficina y enviar su salida PDF al selector de impresión Android.
- Los archivos `.doc` binarios antiguos no se editan dentro de MARJHO. Convertirlos a `.docx` o PDF con otra aplicación.
- El selector permite elegir archivos de cualquier tipo; MARJHO reconoce PDF, DOCX, imágenes y texto UTF-8 o UTF-16 con BOM. Para texto plano, detecta también extensiones comunes de programación y configuración (`.md`, `.csv`, `.json`, `.xml`, `.yaml`, `.ini`, `.properties`, `.sql`, `.js`, `.ts`, `.java`, `.kt`, `.py`, `.c`, `.cpp`, `.html`, `.css`, `.sh`, `.ps1`, entre otras). Si no reconoce un formato binario, informa que no tiene editor.
- El editor de esos formatos de texto es texto plano, sin resaltado de sintaxis ni vista previa de Markdown/HTML. Puede guardar como TXT, conservar la extensión de origen en una copia UTF-8 o crear un PDF de lectura para imprimir. En formatos como RTF o HTML, la app edita el texto fuente y no renderiza su formato.
- La importación acepta hasta 40 MB por archivo; el texto editable se limita a 2 MB para evitar agotar memoria al abrirlo en el teléfono. Los archivos con codificación heredada se leen como Windows-1252 cuando la extensión/MIME declara que son texto; las copias se exportan como UTF-8.
- Toda exportación se guarda con el selector de archivos Android.

## Imágenes

El editor permite girar, reflejar, recortar el centro, cambiar brillo y contraste, convertir a escala de grises y exportar PNG, JPEG, WEBP o BMP. La conversión a PDF A4 sigue disponible.

La importación acepta archivos con MIME `image/*` que el decodificador de la versión de Android pueda abrir. GIF puede mostrarse como un cuadro; formatos de cámara o imagen menos comunes dependen del decodificador del dispositivo. «Cualquier formato» no se puede garantizar con los códecs del sistema.

## Dependencias consultadas

- [`gmuth/ipp-client-kotlin`](https://github.com/gmuth/ipp-client-kotlin): cliente Kotlin/Java para IPP, basado en RFC 8010/8011; el proyecto documenta consulta de `document-format-supported` e impresión PDF. MIT. Se usa como dependencia Maven `de.gmuth:ipp-client:3.5.1`.
- [`TomRoush/PdfBox-Android`](https://github.com/TomRoush/PdfBox-Android): adaptación de Apache PDFBox para Android, Apache-2.0. Se usa como `com.tom-roush:pdfbox-android:2.0.27.0`.
- [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2/android): OCR en el dispositivo; se empaqueta el modelo latino como `com.google.mlkit:text-recognition:16.0.1`, de modo que no depende de descargar el modelo ni subir documentos a un servicio remoto.
- [`Tosin-JD/TDoc`](https://github.com/Tosin-JD/TDoc): proyecto Android de investigación para extraer estructura/formato de DOCX y ODT. Su README usa Apache POI y odfdom y declara GPL-3.0; se revisó, pero no se integró porque supondría añadir varias dependencias y obligaciones de licencia. MARJHO mantiene el editor DOCX de texto acotado descrito arriba.
- Android recomienda Storage Access Framework (`ACTION_OPEN_DOCUMENT`) para permitir que la persona elija documentos desde almacenamiento local o proveedores instalados; el editor usa ese selector y solicita cualquier MIME para poder clasificar extensiones que los proveedores etiquetan como `application/octet-stream`.
- Epson publica sus controladores ESC/P-R para Linux como controladores GPL, no como una API Android lista para usar. El flujo Android de este proyecto es un perfil raster experimental para la L4150 y requiere validación física.
- [RFC 8011](https://www.rfc-editor.org/rfc/rfc8011): operaciones IPP `Get-Printer-Attributes` y `Print-Job`.
- [Android PrintService](https://developer.android.com/reference/android/printservice/package-summary) y [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager): contratos del sistema Android y descubrimiento DNS-SD.

## Compilar e instalar

Requisitos del proyecto: JDK 21, Android SDK Platform 36 y Android Studio. La variante debug se genera con:

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Para instalar en un dispositivo conectado por ADB:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

La validación de protocolo en una impresora real y la salida de papel requieren estar en la misma red que el dispositivo de impresión. Una respuesta IPP exitosa o un envío TCP completado confirma recepción del trabajo, no que el papel haya salido correctamente.

La verificación de compilación e instalación de esta iteración se registra en el README. No se añadieron bibliotecas de oficina pesadas para prometer renderizado DOCX completo: el editor actual conserva el paquete, pero no sustituye a Word/LibreOffice para editar diseño y paginación.

La iteración de interfaz del 2026-11-08 se compiló con `:app:assembleDebug`, se instaló en el Xiaomi conectado y se abrió para revisar la sección Red en pantalla real. La app mostró la Epson L4150 detectada y los cuatro destinos de navegación.

### Validación física

El 2026-11-08 se confirmó una página de prueba impresa físicamente desde el Xiaomi conectado, hacia `EPSON L4150 Series` en `192.168.1.14`, usando ESC/P-R directo por TCP 9100. La app reportó una página transmitida y el usuario confirmó que salió la hoja. Esto valida una página en esa L4150; la ruta multipágina y otros modelos Epson todavía requieren pruebas físicas propias.

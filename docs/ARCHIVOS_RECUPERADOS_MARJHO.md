# Archivos recuperados y migrados

Origen: `MARJHO_ETAPA19C_OPCIONES_BETA_PROYECTO_COMPLETO.zip`.

Migrados al proyecto independiente:
- `EditorEtiquetaState.kt` → `app/src/main/java/com/marjho/printservice/editor/EditorEtiquetaState.kt`
- `EditorEtiquetaCanvas.kt` → `app/src/main/java/com/marjho/printservice/editor/EditorEtiquetaCanvas.kt`

Se implementó una interfaz independiente basada en esos componentes y un generador PDF propio para evitar arrastrar al complemento dependencias de catálogo, Room, Firebase, modelos de producto y utilidades que no pertenecen al servicio de impresión.

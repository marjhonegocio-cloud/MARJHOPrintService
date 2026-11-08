package com.marjho.printservice

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun PdfViewerScreen(
    bitmap: Bitmap?,
    pagina: Int,
    totalPaginas: Int,
    cargando: Boolean,
    imprimiendo: Boolean,
    mensaje: String?,
    onCambiarPagina: (Int) -> Unit,
    onCompartir: () -> Unit,
    onImprimir: () -> Unit
) {
    var controlesVisibles by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(if (controlesVisibles) 1f else 0.12f, label = "controles PDF")

    LaunchedEffect(controlesVisibles, pagina, mensaje) {
        if (controlesVisibles) {
            delay(3200)
            controlesVisibles = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF171717)).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed }) controlesVisibles = true
                }
            }
        }
    ) {
        bitmap?.let { paginaPdf ->
            Image(
                bitmap = paginaPdf.asImageBitmap(),
                contentDescription = "Página ${pagina + 1} de $totalPaginas",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 28.dp)
                    .pointerInput(pagina, totalPaginas) {
                        var desplazamiento = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { desplazamiento = 0f; controlesVisibles = true },
                            onHorizontalDrag = { change, cantidad -> desplazamiento += cantidad; change.consume() },
                            onDragEnd = {
                                if (desplazamiento < -72f && pagina < totalPaginas - 1) onCambiarPagina(pagina + 1)
                                if (desplazamiento > 72f && pagina > 0) onCambiarPagina(pagina - 1)
                            }
                        )
                    }
            )
        }

        if (cargando && bitmap == null) CircularProgressIndicator(Modifier.align(Alignment.Center))

        if (controlesVisibles && totalPaginas > 0) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape
            ) {
                Text("${pagina + 1} / $totalPaginas", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White)
            }
        }

        if (!mensaje.isNullOrBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(mensaje, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), maxLines = 3)
            }
        }

        if (totalPaginas > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .graphicsLayer { this.alpha = alpha },
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallFloatingActionButton(
                    onClick = onCompartir,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Compartir PDF" }
                ) { Text("↗", style = MaterialTheme.typography.titleLarge) }
                SmallFloatingActionButton(
                    onClick = onImprimir,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Imprimir PDF directamente" }
                ) {
                    if (imprimiendo) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("⎙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

package com.marcmayol.dracpaste.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vista de cámara que lee un QR y avisa una sola vez.
 *
 * Se usa el modelo de códigos de barras **embebido en el APK**, no el de Google Play
 * Services: emparejar no puede depender de descargar nada en ese momento, ni de que Play
 * Services esté instalado. Es más peso en el APK a cambio de que funcione siempre.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun EscanerQr(
    modifier: Modifier = Modifier,
    alLeer: (String) -> Unit,
) {
    val contexto = LocalContext.current
    val duenoDelCiclo = LocalLifecycleOwner.current

    // Un QR se lee varias veces por segundo mientras esté delante de la cámara. Sin esto,
    // el emparejamiento se intentaría diez veces con el mismo token —del que solo el
    // primero valdría— y el usuario vería una ristra de errores.
    val yaLeido = remember { AtomicBoolean(false) }
    val ejecutor = remember { Executors.newSingleThreadExecutor() }
    val lector = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            ejecutor.shutdown()
            lector.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val vista = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val futuro = ProcessCameraProvider.getInstance(ctx)
            futuro.addListener({
                val proveedor = futuro.get()

                val previa = Preview.Builder().build().also {
                    it.setSurfaceProvider(vista.surfaceProvider)
                }

                val analisis = ImageAnalysis.Builder()
                    // Solo interesa el fotograma más reciente: acumular una cola de
                    // imágenes viejas solo añade retardo.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analisis.setAnalyzer(ejecutor) { imagen ->
                    val medio = imagen.image
                    if (medio == null || yaLeido.get()) {
                        imagen.close()
                        return@setAnalyzer
                    }

                    lector.process(InputImage.fromMediaImage(medio, imagen.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codigos ->
                            val texto = codigos
                                .firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                ?.rawValue

                            if (texto != null && yaLeido.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(ctx).execute { alLeer(texto) }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.d(TAG, "Fotograma sin código legible", e)
                        }
                        .addOnCompleteListener { imagen.close() }
                }

                try {
                    proveedor.unbindAll()
                    proveedor.bindToLifecycle(
                        duenoDelCiclo,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previa,
                        analisis,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir la cámara", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            vista
        },
    )
}

private const val TAG = "DracPaste.Escaner"

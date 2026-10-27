package com.hisname.productrender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hisname.productrender.glb.BottlePrint
import com.hisname.productrender.glb.Glb
import com.hisname.productrender.glb.PrintImage
import com.hisname.productrender.viewer.ModelViewer
import com.hisname.productrender.viewer.ViewerCapture
import com.hisname.productrender.viewer.savePng
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.reload.DevelopmentEntryPoint

@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            ProductRender(Modifier.safeContentPadding().fillMaxSize())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductRender(modifier: Modifier = Modifier) {
    var model by remember { mutableStateOf<ByteArray?>(null) }
    var print by remember { mutableStateOf<PrintImage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val capture = remember { ViewerCapture() }

    val canPrint = remember(model) { model?.let { BottlePrint.hasPrintArea(it) } ?: false }
    // The model as shown: the uploaded GLB with the image on its print area, if there is one
    val shown by produceState<ByteArray?>(null, model, print) {
        val glb = model
        val image = print
        value = if (glb == null || image == null) {
            glb
        } else {
            working = true
            try {
                withContext(Dispatchers.Default) { BottlePrint.apply(glb, image) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't put the image on the bottle: ${e.message}"
                glb
            } finally {
                working = false
            }
        }
    }

    val modelPicker = rememberFilePickerLauncher(type = FileKitType.File("glb")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            working = true
            try {
                val bytes = file.readBytes()
                Glb.parse(bytes) // rejects anything that isn't a readable GLB
                model = bytes
                print = null
                error = null
            } catch (e: Exception) {
                error = "${file.name} can't be shown: ${e.message}"
            } finally {
                working = false
            }
        }
    }
    val imagePicker = rememberFilePickerLauncher(type = FileKitType.File("png", "jpg", "jpeg")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            try {
                print = PrintImage.read(file.readBytes())
                error = null
            } catch (e: Exception) {
                error = "${file.name} can't be used: ${e.message}"
            }
        }
    }

    val exportImage: () -> Unit = {
        scope.launch {
            try {
                val png = capture.png() ?: error("There is nothing rendered to save")
                savePng(png, "product-render")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't export the image: ${e.message}"
            }
        }
    }

    Column(modifier) {
        if (model != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { imagePicker.launch() }, enabled = canPrint && !working) {
                    Text(if (print == null) "Upload image" else "Replace image")
                }
                if (print != null) {
                    OutlinedButton(onClick = { print = null }, enabled = !working) { Text("Remove image") }
                }
                OutlinedButton(onClick = { modelPicker.launch() }, enabled = !working) { Text("Replace GLB") }
                OutlinedButton(onClick = exportImage, enabled = !working) { Text("Export Image") }
            }
            if (!canPrint) {
                Message("This model has no '${BottlePrint.MATERIAL}' material, so an image can't be put on it.")
            }
        }
        error?.let { Message(it, isError = true) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val glb = shown
            if (glb == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Upload a GLB file to see it rendered", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { modelPicker.launch() }, enabled = !working) { Text("Upload GLB") }
                }
            } else {
                ModelViewer(glb, capture, Modifier.fillMaxSize(), onError = { error = it })
            }
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

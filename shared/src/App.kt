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
import com.hisname.productrender.glb.Glb
import com.hisname.productrender.glb.MaterialPicker
import com.hisname.productrender.glb.MaterialPrint
import com.hisname.productrender.glb.PrintImage
import com.hisname.productrender.glb.Ray
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
fun App(shared: suspend () -> SharedView? = { null }, shareLink: (suspend (ByteArray, Map<Int, PrintImage>) -> Int)? = null) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            ProductRender(shared, shareLink, Modifier.safeContentPadding().fillMaxSize())
        }
    }
}

/** A model and the images on its materials (by material index) the app starts with, for example from a shared link. */
class SharedView(val model: ByteArray, val prints: Map<Int, PrintImage>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductRender(shared: suspend () -> SharedView?, shareLink: (suspend (ByteArray, Map<Int, PrintImage>) -> Int)?, modifier: Modifier = Modifier) {
    var model by remember { mutableStateOf<ByteArray?>(null) }
    var prints by remember { mutableStateOf<Map<Int, PrintImage>>(emptyMap()) }
    // The material whose image is being chosen
    var choosingFor by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val capture = remember { ViewerCapture() }

    LaunchedEffect(Unit) {
        working = true
        try {
            shared()?.let {
                Glb.parse(it.model)
                model = it.model
                prints = it.prints
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "The shared link can't be shown: ${e.message}"
        } finally {
            working = false
        }
    }

    val picker = remember(model) { model?.let { MaterialPicker(it) } }
    // The model as shown: the uploaded GLB with each image on its material
    val shown by produceState<ByteArray?>(null, model, prints) {
        val glb = model
        val images = prints
        value = if (glb == null || images.isEmpty()) {
            glb
        } else {
            working = true
            try {
                withContext(Dispatchers.Default) { MaterialPrint.apply(glb, images) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't put the images on the model: ${e.message}"
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
                prints = emptyMap()
                error = null
            } catch (e: Exception) {
                error = "${file.name} can't be shown: ${e.message}"
            } finally {
                working = false
            }
        }
    }
    val imagePicker = rememberFilePickerLauncher(type = FileKitType.File("png", "jpg", "jpeg")) { file ->
        val material = choosingFor
        choosingFor = null
        if (file == null || material == null) return@rememberFilePickerLauncher
        scope.launch {
            try {
                prints = prints + (material to PrintImage.read(file.readBytes()))
                error = null
            } catch (e: Exception) {
                error = "${file.name} can't be used: ${e.message}"
            }
        }
    }

    // A click on a material's surface asks for the image to put on it
    val chooseImageAt: (Ray) -> Unit = { ray ->
        val glb = model
        val currentPicker = picker
        if (glb != null && currentPicker != null && !working && choosingFor == null) scope.launch {
            try {
                val material = withContext(Dispatchers.Default) { currentPicker.materialAt(ray) } ?: return@launch
                if (MaterialPrint.canPrint(glb, material)) {
                    choosingFor = material
                    imagePicker.launch()
                } else {
                    error = "'${MaterialPrint.materialName(glb, material)}' has no texture coordinates, so an image can't be put on it."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't tell what was clicked: ${e.message}"
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

    val copyLink: () -> Unit = {
        val glb = model
        if (glb != null && shareLink != null) scope.launch {
            working = true
            try {
                val kb = shareLink(glb, prints)
                notice = "Link copied ($kb KB). It holds the model and images, so anyone it reaches can open it."
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't make the link: ${e.message}"
            } finally {
                working = false
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
                if (prints.isNotEmpty()) {
                    OutlinedButton(onClick = { prints = emptyMap() }, enabled = !working) { Text("Remove images") }
                }
                OutlinedButton(onClick = { modelPicker.launch() }, enabled = !working) { Text("Replace GLB") }
                OutlinedButton(onClick = exportImage, enabled = !working) { Text("Export Image") }
                if (shareLink != null) OutlinedButton(onClick = copyLink, enabled = !working) { Text("Copy link") }
            }
            Message("Click a surface of the model to put an image on it.")
        }
        notice?.let { Message(it) }
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
                ModelViewer(glb, capture, Modifier.fillMaxSize(), onError = { error = it }, onTap = chooseImageAt)
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

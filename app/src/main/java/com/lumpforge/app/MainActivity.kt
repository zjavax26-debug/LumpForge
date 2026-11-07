package com.lumpforge.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LumpForgeApp()
            }
        }
    }
}

private data class BrowserEntry(
    val document: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mimeType: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumpForgeApp() {
    val context = LocalContext.current
    var rootFolder by remember { mutableStateOf<DocumentFile?>(null) }
    var currentFolder by remember { mutableStateOf<DocumentFile?>(null) }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var openedWad by remember { mutableStateOf<BrowserEntry?>(null) }
    var wadArchive by remember { mutableStateOf<WadArchive?>(null) }
    var selectedLump by remember { mutableStateOf<WadLump?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewMessage by remember { mutableStateOf("Selecciona un recurso para intentar previsualizarlo.") }
    var cachedDoomPalette by remember { mutableStateOf<ByteArray?>(null) }
    var cachedPaletteSource by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant only temporary access.
            }
            val folder = DocumentFile.fromTreeUri(context, uri)
            rootFolder = folder
            currentFolder = folder
            selectedEntry = null
            openedWad = null
            wadArchive = null
        }
    }

    LaunchedEffect(currentFolder) {
        val folder = currentFolder
        if (folder == null) {
            entries = emptyList()
            return@LaunchedEffect
        }

        isLoading = true
        loadError = null
        selectedEntry = null

        try {
            val children = withContext(Dispatchers.IO) {
                folder.listFiles()
                    .map { file ->
                        BrowserEntry(
                            document = file,
                            name = file.name ?: "Archivo sin nombre",
                            isDirectory = file.isDirectory,
                            size = file.length(),
                            mimeType = file.type
                        )
                    }
                    .sortedWith(
                        compareByDescending<BrowserEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )
            }
            entries = children
        } catch (error: Exception) {
            entries = emptyList()
            loadError = error.localizedMessage ?: "No se pudo leer el contenido."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(openedWad?.document?.uri) {
        val wad = openedWad
        if (wad == null) {
            wadArchive = null
            selectedLump = null
            return@LaunchedEffect
        }

        isLoading = true
        loadError = null
        wadArchive = null
        selectedLump = null

        try {
            val parsed = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(wad.document.uri)
                    ?: throw IllegalStateException("Android no pudo abrir este archivo.")
                input.use { WadReader.read(it, wad.size) }
            }
            wadArchive = parsed
            val paletteLump = parsed.lumps.firstOrNull {
                it.name.equals("PLAYPAL", ignoreCase = true)
            }
            if (paletteLump != null && paletteLump.size >= 768L) {
                val palette = withContext(Dispatchers.IO) {
                    readWadBytes(context, wad, paletteLump.offset, 768)
                }
                cachedDoomPalette = palette
                cachedPaletteSource = wad.name
            }
        } catch (error: Exception) {
            loadError = error.localizedMessage ?: "No se pudo interpretar el WAD."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(
        openedWad?.document?.uri,
        selectedLump?.index,
        wadArchive?.lumps?.size,
        cachedPaletteSource
    ) {
        val wad = openedWad
        val lump = selectedLump
        val archive = wadArchive
        previewBitmap = null

        if (wad == null || lump == null || archive == null) {
            previewMessage = "Selecciona un recurso para intentar previsualizarlo."
        } else {
            val selectedCategory = lumpCategory(archive.lumps, lump.index)
            if (selectedCategory == "Definición de textura") {
                previewMessage = "Este recurso contiene definiciones de texturas, no una imagen individual. La vista previa de texturas compuestas se incorporará en una etapa posterior."
            } else if (selectedCategory == "Script") {
                previewMessage = "Este recurso contiene datos de script y no es una imagen."
            } else {
            previewMessage = "Preparando vista previa…"
            try {
                val result = withContext(Dispatchers.IO) {
                    if (lump.size !in 8L..(8L * 1024L * 1024L)) {
                        throw IllegalArgumentException("El recurso está vacío o supera el límite de vista previa.")
                    }
                    val paletteLump = archive.lumps.firstOrNull {
                        it.name.equals("PLAYPAL", ignoreCase = true)
                    }
                    val palette = if (paletteLump != null && paletteLump.size >= 768L) {
                        readWadBytes(context, wad, paletteLump.offset, 768)
                    } else {
                        cachedDoomPalette
                            ?: throw IllegalStateException(
                                "Este WAD no contiene PLAYPAL. Abre primero DOOM.WAD para cargar su paleta."
                            )
                    }
                    val bytes = readWadBytes(context, wad, lump.offset, lump.size.toInt())
                    WadGraphicDecoder.decodePatch(bytes, palette)
                        ?: throw IllegalArgumentException("Este recurso no parece ser una imagen tipo Doom patch compatible.")
                }
                previewBitmap = result
                previewMessage = if (
                    archive.lumps.none { it.name.equals("PLAYPAL", ignoreCase = true) }
                ) {
                    "Vista previa con la paleta de ${cachedPaletteSource ?: "IWAD"}."
                } else {
                    "Vista previa de imagen Doom."
                }
            } catch (error: Exception) {
                previewMessage = error.localizedMessage ?: "No se pudo generar la vista previa."
            }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LumpForge", fontWeight = FontWeight.SemiBold)
                        Text(
                            openedWad?.name ?: currentFolder?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("Elegir carpeta")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (openedWad != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            openedWad = null
                            wadArchive = null
                            selectedLump = null
                            loadError = null
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                val stroke = 2.dp.toPx()
                                val midY = size.height / 2f
                                drawLine(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    start = Offset(size.width * 0.88f, midY),
                                    end = Offset(size.width * 0.18f, midY),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    start = Offset(size.width * 0.18f, midY),
                                    end = Offset(size.width * 0.48f, midY - size.height * 0.30f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    start = Offset(size.width * 0.18f, midY),
                                    end = Offset(size.width * 0.48f, midY + size.height * 0.30f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                            }
                            Text("Volver")
                        }
                    }
                    Text(
                        wadArchive?.let { "${it.format} · ${it.lumps.size} recursos" }
                            ?: "Leyendo directorio WAD…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            } else if (rootFolder != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val parent = currentFolder?.parentFile
                            if (parent != null) currentFolder = parent
                            else currentFolder = rootFolder
                        },
                        enabled = currentFolder?.uri != rootFolder?.uri
                    ) {
                        Text("↑  Arriba")
                    }
                    Text(
                        "${entries.size} elementos",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("Cambiar")
                    }
                }
                HorizontalDivider()
            }

            when {
                rootFolder == null -> EmptyFolderState(
                    onChooseFolder = { folderPicker.launch(null) }
                )

                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                loadError != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(loadError ?: "Error", color = MaterialTheme.colorScheme.error)
                }

                openedWad != null && wadArchive != null -> {
                    WadContents(
                        lumps = wadArchive!!.lumps,
                        selectedLump = selectedLump,
                        previewBitmap = previewBitmap,
                        previewMessage = previewMessage,
                        onSelect = { selectedLump = it }
                    )
                }

                openedWad != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No se pudo cargar el directorio WAD.")
                }

                entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Esta carpeta está vacía.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries, key = { it.document.uri.toString() }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = selectedEntry?.document?.uri == entry.document.uri,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentFolder = entry.document
                                } else if (entry.name.substringAfterLast('.', "")
                                        .equals("wad", ignoreCase = true)) {
                                    selectedEntry = null
                                    openedWad = entry
                                } else {
                                    selectedEntry = entry
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }

            selectedEntry?.let { entry ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Archivo seleccionado", fontWeight = FontWeight.SemiBold)
                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatSize(entry.size)} · ${entry.mimeType ?: "Tipo desconocido"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (entry.name.substringAfterLast('.', "").equals("wad", true)) {
                            Text(
                                "Toca el archivo para explorar sus recursos internos.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WadContents(
    lumps: List<WadLump>,
    selectedLump: WadLump?,
    previewBitmap: Bitmap?,
    previewMessage: String,
    onSelect: (WadLump) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Nombre", fontWeight = FontWeight.SemiBold)
            Text("Tamaño", fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lumps, key = { it.index }) { lump ->
                val selected = selectedLump?.index == lump.index
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(lump) },
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val category = lumpCategory(lumps, lump.index)
                        Text(lumpGlyph(category))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lump.name.ifBlank { "(sin nombre)" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${category} · #${lump.index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(formatSize(lump.size))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        selectedLump?.let { lump ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Recurso seleccionado", fontWeight = FontWeight.SemiBold)
                    Text(lump.name.ifBlank { "(sin nombre)" })
                    Text("Índice: ${lump.index} · ${lumpCategory(lumps, lump.index)}")
                    Text("Offset: ${lump.offset} bytes")
                    Text("Tamaño: ${formatSize(lump.size)}")
                    Text(
                        previewMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Vista previa de ${lump.name}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderState(onChooseFolder: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Explora tus archivos", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Elige una carpeta para comenzar a explorar WAD, PK3, ZIP y otros recursos de juegos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onChooseFolder) {
                Text("Seleccionar carpeta")
            }
            Text(
                "LumpForge solo accederá a la carpeta que autorices.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: BrowserEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (entry.isDirectory) "📁" else fileGlyph(entry.name))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (entry.isDirectory) "Carpeta" else formatSize(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Text("Seleccionado", style = MaterialTheme.typography.labelSmall)
            } else if (entry.isDirectory) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            } else if (entry.name.substringAfterLast('.', "").equals("wad", true)) {
                Text("Abrir", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


private fun readWadBytes(
    context: android.content.Context,
    wad: BrowserEntry,
    offset: Long,
    length: Int
): ByteArray {
    require(offset >= 0 && length >= 0 && offset + length <= wad.size) {
        "El recurso está fuera de los límites del WAD."
    }
    val input = context.contentResolver.openInputStream(wad.document.uri)
        ?: throw IllegalStateException("Android no pudo abrir el WAD.")
    input.use { stream ->
        var remaining = offset
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (stream.read() == -1) throw java.io.EOFException("Fin inesperado del WAD.")
                remaining--
            }
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = stream.read(bytes, read, length - read)
            if (count < 0) throw java.io.EOFException("Recurso WAD incompleto.")
            read += count
        }
        return bytes
    }
}

private fun lumpCategory(lumps: List<WadLump>, index: Int): String {
    val name = lumps.getOrNull(index)?.name.orEmpty().uppercase(Locale.ROOT)
    val markerNames = setOf(
        "F_START", "FF_START", "F_END", "FF_END",
        "S_START", "SS_START", "S_END", "SS_END",
        "P_START", "PP_START", "P_END", "PP_END"
    )
    if (name in markerNames || name.matches(Regex("[PFS][0-9]_(START|END)"))) {
        return "Marcador"
    }

    var section: String? = null
    for (i in 0 until index) {
        when (lumps[i].name.uppercase(Locale.ROOT)) {
            "S_START", "SS_START" -> section = "Gráfico / sprite"
            "S_END", "SS_END" -> if (section == "Gráfico / sprite") section = null
            "F_START", "FF_START" -> section = "Textura de piso"
            "F_END", "FF_END" -> if (section == "Textura de piso") section = null
            "P_START", "PP_START" -> section = "Parche gráfico"
            "P_END", "PP_END" -> if (section == "Parche gráfico") section = null
        }
    }
    if (section != null) return section

    return when {
        name.matches(Regex("E[1-4]M[1-9][0-9]?")) ||
            name.matches(Regex("MAP[0-9]{2}")) -> "Mapa"
        name in setOf("THINGS", "LINEDEFS", "SIDEDEFS", "VERTEXES",
            "SEGS", "SSECTORS", "NODES", "SECTORS", "REJECT", "BLOCKMAP") -> "Datos de mapa"
        name in setOf("BEHAVIOR", "SCRIPTS", "SCRIPT") ||
            name.endsWith(".ACS") -> "Script"
        name.startsWith("DS") -> "Sonido"
        name.startsWith("D_") -> "Música"
        name.startsWith("PLAY") || name.startsWith("TROO") ||
            name.startsWith("SARG") || name.startsWith("POSS") -> "Gráfico / sprite"
        name == "PNAMES" || name.startsWith("TEXTURE") -> "Definición de textura"
        else -> "Recurso"
    }
}

private fun lumpGlyph(category: String): String {
    return when (category) {
        "Mapa", "Datos de mapa" -> "🗺️"
        "Gráfico / sprite", "Textura de piso", "Parche gráfico" -> "🖼️"
        "Sonido", "Música" -> "🔊"
        "Marcador" -> "📌"
        "Definición de textura" -> "🎨"
        else -> "📦"
    }
}

private fun fileGlyph(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "wad", "pk3", "pk7", "zip" -> "🧩"
        "png", "jpg", "jpeg", "bmp", "gif" -> "🖼️"
        "ogg", "wav", "mp3", "flac" -> "🔊"
        "txt", "cfg", "ini", "lua", "acs" -> "📄"
        else -> "📦"
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

package com.lumpforge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Color
import androidx.documentfile.provider.DocumentFile
import dev.kotlinds.fluidsynthkmp.MidiFilePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
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

private data class WadLumpSource(val file: BrowserEntry, val lump: WadLump)

private data class LoadedBaseWad(
    val file: BrowserEntry,
    val archive: WadArchive,
    val palette: ByteArray?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumpForgeApp() {
    val context = LocalContext.current
    val audioPreferences = remember {
        context.getSharedPreferences("lumpforge_audio", Context.MODE_PRIVATE)
    }
    var soundFontUriString by remember {
        mutableStateOf(audioPreferences.getString("soundfont_uri", null))
    }
    var soundFontDisplayName by remember {
        mutableStateOf(audioPreferences.getString("soundfont_name", null))
    }
    var midiPlayer by remember { mutableStateOf<MidiFilePlayer?>(null) }
    var activeMidiFile by remember { mutableStateOf<File?>(null) }
    var musicPlaybackMonitor by remember { mutableStateOf<Job?>(null) }
    var musicPlaybackState by remember { mutableStateOf("stopped") }
    var playingMusicIndex by remember { mutableStateOf<Int?>(null) }
    var playingMusicWadUri by remember { mutableStateOf<String?>(null) }
    var rootFolder by remember { mutableStateOf<DocumentFile?>(null) }
    var currentFolder by remember { mutableStateOf<DocumentFile?>(null) }
    var entries by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var openedWad by remember { mutableStateOf<BrowserEntry?>(null) }
    var wadArchive by remember { mutableStateOf<WadArchive?>(null) }
    var selectedLump by remember { mutableStateOf<WadLump?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var mapPreviewData by remember { mutableStateOf<WadMapPreviewData?>(null) }
    var previewMessage by remember { mutableStateOf("Selecciona un recurso para intentar previsualizarlo.") }
    var loadedBaseWads by remember { mutableStateOf<List<LoadedBaseWad>>(emptyList()) }
    var wadTextures by remember { mutableStateOf<List<WadTextureDefinition>>(emptyList()) }
    var selectedTexture by remember { mutableStateOf<WadTextureDefinition?>(null) }
    var soundPlaybackJob by remember { mutableStateOf<Job?>(null) }
    var playingSoundIndex by remember { mutableStateOf<Int?>(null) }
    var musicMidiBytes by remember { mutableStateOf<ByteArray?>(null) }
    var musicDetails by remember { mutableStateOf<String?>(null) }
    var pendingMidiBytes by remember { mutableStateOf<ByteArray?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var isSavingMap by remember { mutableStateOf(false) }
    var pendingMapToSelect by remember { mutableStateOf<String?>(null) }

    suspend fun stopCurrentMusic() {
        musicPlaybackMonitor?.cancel()
        musicPlaybackMonitor = null
        val player = midiPlayer
        midiPlayer = null
        val midiFile = activeMidiFile
        activeMidiFile = null
        musicPlaybackState = "stopped"
        playingMusicIndex = null
        playingMusicWadUri = null
        if (player != null) {
            withContext(Dispatchers.IO) {
                runCatching { player.stop() }
                runCatching { player.close() }
            }
        }
        midiFile?.delete()
    }

    fun watchMusicPlayback(player: MidiFilePlayer) {
        musicPlaybackMonitor?.cancel()
        musicPlaybackMonitor = coroutineScope.launch {
            delay(350)
            while (isActive && midiPlayer === player && musicPlaybackState == "playing") {
                val stillPlaying = runCatching {
                    withContext(Dispatchers.IO) { player.isPlaying }
                }.getOrDefault(false)

                if (!stillPlaying) {
                    val finishedFile = activeMidiFile
                    activeMidiFile = null
                    midiPlayer = null
                    playingMusicIndex = null
                    playingMusicWadUri = null
                    musicPlaybackState = "completed"
                    withContext(Dispatchers.IO) {
                        runCatching { player.close() }
                    }
                    finishedFile?.delete()
                    previewMessage = "Reproducción musical finalizada."
                    break
                }
                delay(180)
            }
        }
    }
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            soundPlaybackJob?.cancel()
            musicPlaybackMonitor?.cancel()
            midiPlayer?.let { player ->
                runCatching { player.stop() }
                runCatching { player.close() }
            }
            activeMidiFile?.delete()
        }
    }

    LaunchedEffect(openedWad?.document?.uri, selectedLump?.index) {
        if (playingSoundIndex != null && playingSoundIndex != selectedLump?.index) {
            soundPlaybackJob?.cancel()
            soundPlaybackJob = null
            playingSoundIndex = null
        }
        if (playingMusicIndex != null &&
            (playingMusicIndex != selectedLump?.index ||
                playingMusicWadUri != openedWad?.document?.uri?.toString())
        ) {
            stopCurrentMusic()
        }
    }

    val toggleSoundPlayback: (WadLump) -> Unit = { soundLump ->
        val activeWad = openedWad
        if (playingSoundIndex == soundLump.index && soundPlaybackJob != null) {
            soundPlaybackJob?.cancel()
            soundPlaybackJob = null
            playingSoundIndex = null
            previewMessage = "Reproducción detenida."
        } else if (activeWad != null) {
            soundPlaybackJob?.cancel()
            playingSoundIndex = soundLump.index
            previewMessage = "Cargando efecto de sonido…"

            lateinit var playback: Job
            playback = coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val sound = withContext(Dispatchers.IO) {
                        require(soundLump.size in 8L..(16L * 1024L * 1024L)) {
                            "El sonido está vacío o supera el límite de lectura."
                        }
                        val bytes = readWadBytes(
                            context, activeWad, soundLump.offset, soundLump.size.toInt()
                        )
                        WadSoundReader.parse(bytes)
                    }
                    previewMessage = "Reproduciendo DMX · ${sound.sampleRate} Hz · " +
                        String.format(Locale.getDefault(), "%.1f s", sound.durationMillis / 1000.0)
                    WadSoundPlayer.play(sound)
                    previewMessage = "Reproducción finalizada."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    previewMessage = error.localizedMessage
                        ?: "No se pudo reproducir este sonido."
                } finally {
                    if (soundPlaybackJob === playback) {
                        soundPlaybackJob = null
                        playingSoundIndex = null
                    }
                }
            }
            soundPlaybackJob = playback
            playback.start()
        }
    }

    val soundFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val displayName = SoundFontFileManager.displayName(context, uri)
                require(displayName.endsWith(".sf2", ignoreCase = true)) {
                    "Selecciona un archivo SoundFont con extensión .sf2."
                }
                coroutineScope.launch {
                    stopCurrentMusic()
                    soundFontUriString = uri.toString()
                    soundFontDisplayName = displayName
                    audioPreferences.edit()
                        .putString("soundfont_uri", uri.toString())
                        .putString("soundfont_name", displayName)
                        .apply()
                    previewMessage = "SoundFont seleccionado: $displayName."
                }
            } catch (error: Exception) {
                previewMessage = error.localizedMessage
                    ?: "No se pudo seleccionar el SoundFont."
            }
        }
    }

    val toggleMusicPlayback: () -> Unit = {
        coroutineScope.launch {
            val currentPlayer = midiPlayer
            when {
                currentPlayer != null && musicPlaybackState == "playing" -> {
                    musicPlaybackMonitor?.cancel()
                    musicPlaybackMonitor = null
                    try {
                        withContext(Dispatchers.IO) { currentPlayer.pause() }
                        musicPlaybackState = "paused"
                        previewMessage = "Música en pausa."
                    } catch (error: Exception) {
                        stopCurrentMusic()
                        previewMessage = error.localizedMessage
                            ?: "No se pudo pausar la música."
                    }
                }

                currentPlayer != null && musicPlaybackState == "paused" -> {
                    try {
                        withContext(Dispatchers.IO) { currentPlayer.play() }
                        musicPlaybackState = "playing"
                        previewMessage = "Reanudando música con ${soundFontDisplayName ?: "SoundFont"}."
                        watchMusicPlayback(currentPlayer)
                    } catch (error: Exception) {
                        stopCurrentMusic()
                        previewMessage = error.localizedMessage
                            ?: "No se pudo reanudar la música."
                    }
                }

                else -> {
                    val selectedSoundFont = soundFontUriString
                    val selectedSoundFontName = soundFontDisplayName
                    val midiBytes = musicMidiBytes
                    val musicName = selectedLump?.name ?: "DoomMusic"
                    val musicIndex = selectedLump?.index ?: -1
                    val wadUri = openedWad?.document?.uri?.toString()

                    if (selectedSoundFont == null || selectedSoundFontName == null) {
                        previewMessage = "Primero selecciona un SoundFont (.sf2)."
                        return@launch
                    }
                    if (midiBytes == null) {
                        previewMessage = "La pista musical todavía no está preparada."
                        return@launch
                    }

                    musicPlaybackState = "loading"
                    previewMessage = "Cargando SoundFont y preparando el sintetizador…"
                    try {
                        val prepared = withContext(Dispatchers.IO) {
                            val soundFontFile = SoundFontFileManager.copyToPrivateStorage(
                                context,
                                Uri.parse(selectedSoundFont),
                                selectedSoundFontName
                            )
                            val midiFile = SoundFontFileManager.writeMidiToCache(
                                context,
                                musicName,
                                musicIndex,
                                midiBytes
                            )
                            try {
                                MidiFilePlayer(
                                    soundFontFile.absolutePath,
                                    midiFile.absolutePath,
                                    44_100
                                ) to midiFile
                            } catch (error: Exception) {
                                midiFile.delete()
                                throw error
                            }
                        }

                        midiPlayer = prepared.first
                        activeMidiFile = prepared.second
                        playingMusicIndex = musicIndex
                        playingMusicWadUri = wadUri
                        withContext(Dispatchers.IO) { prepared.first.play() }
                        musicPlaybackState = "playing"
                        previewMessage = "Reproduciendo música con $selectedSoundFontName."
                        watchMusicPlayback(prepared.first)
                    } catch (error: Exception) {
                        stopCurrentMusic()
                        previewMessage = error.localizedMessage
                            ?: "No se pudo iniciar el sintetizador FluidSynth."
                    }
                }
            }
        }
    }

    val stopMusicPlayback: () -> Unit = {
        coroutineScope.launch {
            stopCurrentMusic()
            previewMessage = "Reproducción musical detenida."
        }
    }

    val midiExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        val bytes = pendingMidiBytes
        if (uri != null && bytes != null) {
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Android no pudo crear el archivo MIDI.")
                output.use { it.write(bytes) }
                previewMessage = "Archivo MIDI exportado correctamente."
            } catch (error: Exception) {
                previewMessage = error.localizedMessage ?: "No se pudo guardar el MIDI."
            }
        }
        pendingMidiBytes = null
    }


    val saveSectorEdit: (Int, WadMapSector) -> Unit = { sectorIndex, editedSector ->
        coroutineScope.launch {
            if (isSavingMap) return@launch
            isSavingMap = true
            try {
                val sourceWad = openedWad
                    ?: throw IllegalStateException("No hay un WAD abierto.")
                val archive = wadArchive
                    ?: throw IllegalStateException("El directorio WAD todavía no está cargado.")
                val mapData = mapPreviewData
                    ?: throw IllegalStateException("No hay un mapa clásico seleccionado.")
                val mapMarker = selectedLump
                    ?: throw IllegalStateException("Selecciona primero un marcador de mapa.")
                require(
                    mapMarker.name.equals(mapData.mapName, ignoreCase = true) &&
                        WadMapReader.isMapMarker(mapMarker.name)
                ) { "El mapa seleccionado cambió. Vuelve a seleccionarlo antes de guardar." }

                val markerPosition = archive.lumps.indexOfFirst { it.index == mapMarker.index }
                require(markerPosition >= 0) {
                    "No se pudo localizar el marcador " + mapData.mapName + " en el WAD."
                }
                val mapLumps = archive.lumps.drop(markerPosition + 1)
                    .takeWhile { !WadMapReader.isMapMarker(it.name) }
                val sectorsLump = mapLumps.lastOrNull {
                    it.name.equals("SECTORS", ignoreCase = true)
                } ?: throw IllegalStateException("El mapa no contiene el recurso SECTORS.")

                require(sectorsLump.size % WadSectorCodec.RECORD_SIZE == 0L) {
                    "El recurso SECTORS no usa registros Doom clásicos de 26 bytes."
                }
                val sectorCount = sectorsLump.size / WadSectorCodec.RECORD_SIZE
                require(sectorIndex >= 0 && sectorIndex.toLong() < sectorCount) {
                    "El sector seleccionado no existe en el recurso SECTORS."
                }

                val patchOffset = sectorsLump.offset +
                    sectorIndex.toLong() * WadSectorCodec.RECORD_SIZE
                val replacement = WadSectorCodec.encode(editedSector)
                val destinationFolder = sourceWad.document.parentFile ?: currentFolder
                    ?: throw IllegalStateException("No se encontró la carpeta para guardar la copia.")
                val baseName = sourceWad.name.substringBeforeLast('.', sourceWad.name)
                val outputName = baseName + "_editado.wad"

                val savedDocument = withContext(Dispatchers.IO) {
                    val output = WadPatchWriter.copyWithPatch(
                        context = context,
                        sourceUri = sourceWad.document.uri,
                        sourceSize = sourceWad.size,
                        destinationFolder = destinationFolder,
                        requestedName = outputName,
                        patchOffset = patchOffset,
                        replacement = replacement
                    )
                    try {
                        val outputSize = output.length().takeIf { it > 0L } ?: sourceWad.size
                        val outputInput = context.contentResolver.openInputStream(output.uri)
                            ?: throw IllegalStateException("No se pudo verificar la copia guardada.")
                        val verifiedArchive = outputInput.use { WadReader.read(it, outputSize) }
                        val verifiedMarkerPosition = verifiedArchive.lumps.indexOfFirst {
                            it.name.equals(mapData.mapName, ignoreCase = true) &&
                                WadMapReader.isMapMarker(it.name)
                        }
                        require(verifiedMarkerPosition >= 0) {
                            "La copia no conserva el marcador " + mapData.mapName + "."
                        }
                        val verifiedMapLumps = verifiedArchive.lumps
                            .drop(verifiedMarkerPosition + 1)
                            .takeWhile { !WadMapReader.isMapMarker(it.name) }
                        val verifiedEntry = BrowserEntry(
                            document = output,
                            name = output.name ?: outputName,
                            isDirectory = false,
                            size = outputSize,
                            mimeType = output.type
                        )
                        val verifiedMap = WadMapReader.parse(
                            mapData.mapName,
                            verifiedMapLumps
                        ) { lump ->
                            readWadBytes(
                                context,
                                verifiedEntry,
                                lump.offset,
                                lump.size.toInt()
                            )
                        }
                        require(verifiedMap.sectors.getOrNull(sectorIndex) == editedSector) {
                            "La verificación no encontró los valores nuevos en el sector guardado."
                        }
                        output
                    } catch (error: Exception) {
                        output.delete()
                        throw error
                    }
                }

                val savedSize = savedDocument.length().takeIf { it > 0L } ?: sourceWad.size
                val savedEntry = BrowserEntry(
                    document = savedDocument,
                    name = savedDocument.name ?: outputName,
                    isDirectory = false,
                    size = savedSize,
                    mimeType = savedDocument.type
                )
                entries = (entries.filterNot { it.document.uri == savedDocument.uri } + savedEntry)
                    .sortedWith(
                        compareByDescending<BrowserEntry> { it.isDirectory }
                            .thenBy { it.name.lowercase(Locale.ROOT) }
                    )
                pendingMapToSelect = mapData.mapName
                openedWad = savedEntry
                previewMessage = "Sector #" + sectorIndex + " guardado y verificado en " +
                    savedEntry.name + ". El WAD original permanece intacto."
            } catch (error: Exception) {
                previewMessage = error.localizedMessage ?: "No se pudo guardar la copia editada."
            } finally {
                isSavingMap = false
            }
        }
    }

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
            pendingMapToSelect?.let { mapName ->
                selectedLump = parsed.lumps.firstOrNull {
                    it.name.equals(mapName, ignoreCase = true) &&
                        WadMapReader.isMapMarker(it.name)
                }
                pendingMapToSelect = null
            }
            if (parsed.format == "IWAD") {
                val paletteLump = parsed.lumps.lastOrNull {
                    it.name.equals("PLAYPAL", ignoreCase = true)
                }
                val palette = if (paletteLump != null && paletteLump.size >= 768L) {
                    withContext(Dispatchers.IO) {
                        readWadBytes(context, wad, paletteLump.offset, 768)
                    }
                } else {
                    null
                }
                val updated = loadedBaseWads
                    .filterNot { it.file.document.uri == wad.document.uri } +
                    LoadedBaseWad(wad, parsed, palette)
                loadedBaseWads = updated
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
        selectedTexture?.name,
        selectedTexture?.sourceLump,
        wadArchive?.lumps?.size,
        loadedBaseWads.map { it.file.document.uri.toString() }
    ) {
        val wad = openedWad
        val lump = selectedLump
        val archive = wadArchive
        previewBitmap = null
        mapPreviewData = null

        if (wad == null || lump == null || archive == null) {
            previewMessage = "Selecciona un recurso para intentar previsualizarlo."
            wadTextures = emptyList()
            selectedTexture = null
        } else {
            val category = lumpCategory(archive.lumps, lump.index)
            val isClassicTextureTable = lump.name.equals("TEXTURE1", true) ||
                lump.name.equals("TEXTURE2", true)
            val isTextTextureTable = lump.name.equals("TEXTURES", true)

            if (category == "Definición de textura" &&
                (isClassicTextureTable || isTextTextureTable)
            ) {
                if (selectedTexture == null) {
                    previewMessage = if (isTextTextureTable) {
                        "Leyendo definiciones de TEXTURES…"
                    } else {
                        "Leyendo PNAMES y definiciones de texturas…"
                    }
                    try {
                        val definitions = withContext(Dispatchers.IO) {
                            if (isTextTextureTable) {
                                require(lump.size in 1L..(8L * 1024L * 1024L)) {
                                    "TEXTURES está vacío o supera el límite de lectura."
                                }
                                val data = readWadBytes(
                                    context, wad, lump.offset, lump.size.toInt()
                                )
                                WadTextTextureReader.parse(data, "TEXTURES")
                            } else {
                                val pnamesSource = archive.lumps.lastOrNull {
                                    it.name.equals("PNAMES", true)
                                }?.let { WadLumpSource(wad, it) } ?: loadedBaseWads
                                    .asReversed()
                                    .firstNotNullOfOrNull { base ->
                                        base.archive.lumps.lastOrNull {
                                            it.name.equals("PNAMES", true)
                                        }?.let { WadLumpSource(base.file, it) }
                                    } ?: throw IllegalStateException(
                                        "No se encontró PNAMES en el WAD abierto ni en los IWAD cargados."
                                    )

                                require(pnamesSource.lump.size in 4L..(2L * 1024L * 1024L)) {
                                    "PNAMES está vacío o supera el límite de lectura."
                                }
                                val patchNames = WadTextureReader.parsePatchNames(
                                    readWadBytes(
                                        context, pnamesSource.file, pnamesSource.lump.offset,
                                        pnamesSource.lump.size.toInt()
                                    )
                                )
                                val textureLumps = archive.lumps.filter {
                                    it.name.equals("TEXTURE1", true) ||
                                        it.name.equals("TEXTURE2", true)
                                }
                                buildList {
                                    textureLumps.forEach { textureLump ->
                                        require(textureLump.size in 4L..(16L * 1024L * 1024L)) {
                                            "${textureLump.name} supera el límite de lectura."
                                        }
                                        val data = readWadBytes(
                                            context, wad, textureLump.offset,
                                            textureLump.size.toInt()
                                        )
                                        addAll(
                                            WadTextureReader.parseTextureLump(
                                                data, patchNames, textureLump.name
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        wadTextures = definitions
                        previewMessage = if (definitions.isEmpty()) {
                            "No se encontraron definiciones de texturas."
                        } else {
                            "Se encontraron ${definitions.size} texturas. Pulsa «Elegir textura» para explorarlas."
                        }
                    } catch (error: Exception) {
                        wadTextures = emptyList()
                        previewMessage = error.localizedMessage
                            ?: "No se pudieron leer las definiciones de texturas."
                    }
                } else {
                    previewMessage = "Reconstruyendo textura…"
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val ownPalette = archive.lumps.lastOrNull {
                                it.name.equals("PLAYPAL", true)
                            }
                            val palette = if (ownPalette != null && ownPalette.size >= 768L) {
                                readWadBytes(context, wad, ownPalette.offset, 768)
                            } else {
                                loadedBaseWads.asReversed()
                                    .firstNotNullOfOrNull { it.palette }
                            }
                            val patchCache = HashMap<String, WadPatchResolution>()
                            val rendered = WadTextureReader.render(selectedTexture!!) { patchName ->
                                patchCache[patchName] ?: run {
                                    val own = archive.lumps.lastOrNull {
                                        it.name.equals(patchName, true)
                                    }?.let { WadLumpSource(wad, it) }
                                    val base = if (own == null) {
                                        loadedBaseWads.asReversed().firstNotNullOfOrNull { baseWad ->
                                            baseWad.archive.lumps.lastOrNull {
                                                it.name.equals(patchName, true)
                                            }?.let { WadLumpSource(baseWad.file, it) }
                                        }
                                    } else null
                                    val source = own ?: base
                                    val resolution = if (source == null) {
                                        WadPatchResolution(found = false)
                                    } else if (source.lump.size !in 8L..(8L * 1024L * 1024L)) {
                                        WadPatchResolution(found = true)
                                    } else {
                                        val bytes = readWadBytes(
                                            context, source.file, source.lump.offset,
                                            source.lump.size.toInt()
                                        )
                                        WadPatchResolution(
                                            bitmap = WadGraphicDecoder.decodeGraphic(bytes, palette),
                                            found = true
                                        )
                                    }
                                    patchCache[patchName] = resolution
                                    resolution
                                }
                            }
                            rendered
                        }
                        previewBitmap = result.bitmap
                        previewMessage = buildString {
                            append("Textura compuesta ${selectedTexture!!.width} × ${selectedTexture!!.height}.")
                            if (result.missingPatches.isEmpty() &&
                                result.unsupportedPatches.isEmpty()
                            ) {
                                append(" Todos los parches se encontraron y se decodificaron.")
                            } else {
                                if (result.missingPatches.isNotEmpty()) {
                                    append(" Parches inexistentes: ")
                                    append(result.missingPatches.joinToString(", "))
                                    append(".")
                                }
                                if (result.unsupportedPatches.isNotEmpty()) {
                                    append(" Formato gráfico no compatible: ")
                                    append(result.unsupportedPatches.joinToString(", "))
                                    append(".")
                                }
                            }
                        }
                    } catch (error: Exception) {
                        previewMessage = error.localizedMessage
                            ?: "No se pudo reconstruir la textura."
                    }
                }
            } else if (category == "Definición de textura") {
                wadTextures = emptyList()
                selectedTexture = null
                previewMessage = "Este recurso contiene datos de definición, no una imagen individual."
            } else if (category == "Sonido") {
                wadTextures = emptyList()
                selectedTexture = null
                musicMidiBytes = null
                musicDetails = null
                previewBitmap = null
                previewMessage = "Efecto de sonido Doom DMX. Pulsa «Reproducir sonido» para escucharlo."
            } else if (category == "Música") {
                wadTextures = emptyList()
                selectedTexture = null
                previewBitmap = null
                musicMidiBytes = null
                musicDetails = null
                previewMessage = "Analizando pista musical…"
                try {
                    val musicResult = withContext(Dispatchers.IO) {
                        require(lump.size in 4L..(8L * 1024L * 1024L)) {
                            "La pista musical está vacía o supera el límite de lectura."
                        }
                        val bytes = readWadBytes(
                            context, wad, lump.offset, lump.size.toInt()
                        )
                        when {
                            WadMusReader.isMus(bytes) -> {
                                val song = WadMusReader.convert(bytes)
                                val durationSeconds = song.durationMillis / 1000.0
                                Triple(
                                    song.midiBytes,
                                    "Formato MUS convertido a MIDI · " +
                                        "${song.primaryChannels} canales principales · " +
                                        "${song.secondaryChannels} secundarios · " +
                                        "${song.instrumentCount} instrumentos · " +
                                        String.format(
                                            Locale.getDefault(), "%.1f s", durationSeconds
                                        ),
                                    "MUS"
                                )
                            }
                            bytes.size >= 4 &&
                                bytes[0] == 'M'.code.toByte() &&
                                bytes[1] == 'T'.code.toByte() &&
                                bytes[2] == 'h'.code.toByte() &&
                                bytes[3] == 'd'.code.toByte() -> Triple(
                                    bytes,
                                    "La pista ya está en formato Standard MIDI.",
                                    "MIDI"
                                )
                            else -> throw IllegalArgumentException(
                                "El recurso no contiene una pista MUS ni Standard MIDI reconocible."
                            )
                        }
                    }
                    musicMidiBytes = musicResult.first
                    musicDetails = musicResult.second
                    previewMessage = if (soundFontDisplayName != null) {
                        "Pista lista. SoundFont seleccionado: ${soundFontDisplayName}."
                    } else {
                        "Pista lista para exportar. Selecciona un SoundFont .sf2 para reproducirla aquí."
                    }
                } catch (error: Exception) {
                    musicMidiBytes = null
                    musicDetails = null
                    previewMessage = error.localizedMessage
                        ?: "No se pudo interpretar la pista musical."
                }
            } else if (category == "Mapa" && WadMapReader.isMapMarker(lump.name)) {
                wadTextures = emptyList()
                selectedTexture = null
                musicMidiBytes = null
                musicDetails = null
                previewMessage = "Leyendo geometría del mapa…"
                try {
                    val mapData = withContext(Dispatchers.IO) {
                        val markerPosition = archive.lumps.indexOfFirst {
                            it.index == lump.index
                        }
                        require(markerPosition >= 0) {
                            "No se pudo localizar el marcador ${lump.name} en el directorio."
                        }

                        val followingLumps = archive.lumps.drop(markerPosition + 1)
                        val nextMapPosition = followingLumps.indexOfFirst {
                            WadMapReader.isMapMarker(it.name)
                        }
                        val mapLumps = if (nextMapPosition >= 0) {
                            followingLumps.take(nextMapPosition)
                        } else {
                            followingLumps
                        }

                        WadMapReader.parse(lump.name, mapLumps) { mapLump ->
                            readWadBytes(
                                context, wad, mapLump.offset, mapLump.size.toInt()
                            )
                        }
                    }
                    mapPreviewData = mapData
                    previewMessage = buildString {
                        append("${mapData.vertices.size} vértices · ")
                        append("${mapData.lines.size} líneas · ")
                        append("${mapData.things.size} objetos · ")
                        append("${mapData.sectorCount} sectores.")
                        if (mapData.invalidLineCount > 0) {
                            append(" Líneas ignoradas por referencias no válidas: ")
                            append(mapData.invalidLineCount)
                            append(".")
                        }
                    }
                } catch (error: Exception) {
                    mapPreviewData = null
                    previewMessage = error.localizedMessage
                        ?: "No se pudo interpretar la geometría de este mapa."
                }
            } else if (category == "Script") {
                wadTextures = emptyList()
                selectedTexture = null
                previewMessage = "Este recurso contiene datos de script y no es una imagen."
            } else {
                wadTextures = emptyList()
                selectedTexture = null
                previewMessage = "Preparando vista previa…"
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (lump.size !in 8L..(8L * 1024L * 1024L)) {
                            throw IllegalArgumentException(
                                "El recurso está vacío o supera el límite de vista previa."
                            )
                        }
                        val ownPalette = archive.lumps.lastOrNull {
                            it.name.equals("PLAYPAL", true)
                        }
                        val palette = if (ownPalette != null && ownPalette.size >= 768L) {
                            readWadBytes(context, wad, ownPalette.offset, 768)
                        } else {
                            loadedBaseWads.asReversed()
                                .firstNotNullOfOrNull { it.palette }
                        }
                        val bytes = readWadBytes(context, wad, lump.offset, lump.size.toInt())
                        WadGraphicDecoder.decodeGraphic(bytes, palette)
                            ?: throw IllegalArgumentException(
                                "El recurso existe, pero no se reconoce como PNG, imagen compatible ni Doom Patch."
                            )
                    }
                    previewBitmap = result
                    previewMessage = if (
                        archive.lumps.none { it.name.equals("PLAYPAL", true) }
                    ) {
                        "Vista previa con la paleta de ${loadedBaseWads.asReversed().firstOrNull { it.palette != null }?.file?.name ?: "IWAD"}."
                    } else {
                        "Vista previa de imagen Doom."
                    }
                } catch (error: Exception) {
                    previewMessage = error.localizedMessage
                        ?: "No se pudo generar la vista previa."
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
            if (loadedBaseWads.isNotEmpty()) {
                Text(
                    text = "IWAD base cargados (${loadedBaseWads.size}): " +
                        loadedBaseWads.joinToString(", ") { it.file.name },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                        mapPreviewData = mapPreviewData,
                        previewMessage = previewMessage,
                        textures = wadTextures,
                        selectedTexture = selectedTexture,
                        onSelect = {
                            selectedLump = it
                            selectedTexture = null
                            wadTextures = emptyList()
                        },
                        onSelectTexture = { selectedTexture = it },
                        playingSoundIndex = playingSoundIndex,
                        onToggleSoundPlayback = toggleSoundPlayback,
                        musicDetails = musicDetails,
                        canExportMusic = musicMidiBytes != null,
                        soundFontName = soundFontDisplayName,
                        canPlayMusic = musicMidiBytes != null && soundFontUriString != null,
                        musicPlaybackState = musicPlaybackState,
                        onPickSoundFont = { soundFontPicker.launch(arrayOf("*/*")) },
                        onToggleMusicPlayback = toggleMusicPlayback,
                        onStopMusicPlayback = stopMusicPlayback,
                        onExportMusic = {
                            musicMidiBytes?.let { bytes ->
                                pendingMidiBytes = bytes
                                midiExporter.launch(
                                    "${selectedLump?.name?.ifBlank { "DoomMusic" } ?: "DoomMusic"}.mid"
                                )
                            }
                        },
                        isSavingMap = isSavingMap,
                        onSaveSector = saveSectorEdit
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
    mapPreviewData: WadMapPreviewData?,
    previewMessage: String,
    textures: List<WadTextureDefinition>,
    selectedTexture: WadTextureDefinition?,
    playingSoundIndex: Int?,
    musicDetails: String?,
    canExportMusic: Boolean,
    soundFontName: String?,
    canPlayMusic: Boolean,
    musicPlaybackState: String,
    onPickSoundFont: () -> Unit,
    onToggleMusicPlayback: () -> Unit,
    onStopMusicPlayback: () -> Unit,
    onSelect: (WadLump) -> Unit,
    onSelectTexture: (WadTextureDefinition) -> Unit,
    onToggleSoundPlayback: (WadLump) -> Unit,
    onExportMusic: () -> Unit,
    isSavingMap: Boolean,
    onSaveSector: (Int, WadMapSector) -> Unit
) {
    var showTexturePicker by remember { mutableStateOf(false) }

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
                    if (lumpCategory(lumps, lump.index) == "Sonido") {
                        OutlinedButton(
                            onClick = { onToggleSoundPlayback(lump) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (playingSoundIndex == lump.index) "Detener sonido"
                                else "Reproducir sonido"
                            )
                        }
                    }
                    if (lumpCategory(lumps, lump.index) == "Música") {
                        musicDetails?.let { details ->
                            Text(
                                details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "SoundFont: ${soundFontName ?: "Ninguno seleccionado"}",
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(
                            onClick = onPickSoundFont,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (soundFontName == null) "Elegir SoundFont (.sf2)"
                                else "Cambiar SoundFont"
                            )
                        }
                        OutlinedButton(
                            onClick = onToggleMusicPlayback,
                            enabled = canPlayMusic && musicPlaybackState != "loading",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when (musicPlaybackState) {
                                    "playing" -> "Pausar música"
                                    "paused" -> "Reanudar música"
                                    "loading" -> "Preparando sintetizador…"
                                    else -> "Reproducir música"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = onStopMusicPlayback,
                            enabled = musicPlaybackState == "playing" ||
                                musicPlaybackState == "paused" ||
                                musicPlaybackState == "loading",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Detener música")
                        }
                        OutlinedButton(
                            onClick = onExportMusic,
                            enabled = canExportMusic,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Exportar pista MIDI")
                        }
                        Text(
                            "El SoundFont se selecciona desde el almacenamiento y se conserva para futuras sesiones. No se incluye ningún banco de sonidos en la APK.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lump.name.equals("TEXTURE1", true) ||
                        lump.name.equals("TEXTURE2", true) ||
                        lump.name.equals("TEXTURES", true)
                    ) {
                        if (selectedTexture != null) {
                            Text(
                                "Textura elegida: ${selectedTexture.name} · ${selectedTexture.width} × ${selectedTexture.height}",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = { showTexturePicker = true },
                            enabled = textures.isNotEmpty()
                        ) {
                            Text(
                                if (textures.isEmpty()) "No hay texturas para explorar"
                                else if (selectedTexture == null) "Elegir textura (${textures.size})"
                                else "Cambiar textura (${textures.size})"
                            )
                        }
                    }
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
                    mapPreviewData?.let { mapData ->
                        Text(
                            "Vista previa 2D · ${mapData.mapName}",
                            fontWeight = FontWeight.SemiBold
                        )
                        MapPreviewCanvas(
                            data = mapData,
                            isSavingMap = isSavingMap,
                            onSaveSector = onSaveSector
                        )
                        Text(
                            "Líneas del mapa y posiciones de objetos. La vista texturizada y las alturas se incorporarán en otra etapa.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showTexturePicker) {
        AlertDialog(
            onDismissRequest = { showTexturePicker = false },
            title = { Text("Texturas disponibles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${textures.size} definiciones encontradas en ${if (selectedTexture?.sourceLump == "TEXTURES") "TEXTURES" else "TEXTURE1/TEXTURE2"}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(textures) { texture ->
                            TextButton(
                                onClick = {
                                    onSelectTexture(texture)
                                    showTexturePicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        texture.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${texture.width} × ${texture.height} · ${texture.patches.size} parches · ${texture.sourceLump}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTexturePicker = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
private fun MapPreviewCanvas(
    data: WadMapPreviewData,
    isSavingMap: Boolean,
    onSaveSector: (Int, WadMapSector) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val selectedColor = MaterialTheme.colorScheme.primary
    val thingColor = MaterialTheme.colorScheme.tertiary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    var showThings by remember(data.mapName) { mutableStateOf(true) }
    var userZoom by remember(data.mapName) { mutableStateOf(1f) }
    var userPan by remember(data.mapName) { mutableStateOf(Offset.Zero) }
    var selectedLineIndex by remember(data.mapName) { mutableStateOf<Int?>(null) }
    var selectedThingIndex by remember(data.mapName) { mutableStateOf<Int?>(null) }
    var selectedSectorIndex by remember(data.mapName) { mutableStateOf<Int?>(null) }
    var showElementDetails by remember(data.mapName) { mutableStateOf(false) }
    var showSectorPicker by remember(data.mapName) { mutableStateOf(false) }
    var showSectorDetails by remember(data.mapName) { mutableStateOf(false) }
    var showSectorEditor by remember(data.mapName) { mutableStateOf(false) }
    var sectorEditFloorHeight by remember(data.mapName) { mutableStateOf("") }
    var sectorEditCeilingHeight by remember(data.mapName) { mutableStateOf("") }
    var sectorEditFloorTexture by remember(data.mapName) { mutableStateOf("") }
    var sectorEditCeilingTexture by remember(data.mapName) { mutableStateOf("") }
    var sectorEditLightLevel by remember(data.mapName) { mutableStateOf("") }
    var sectorEditSpecial by remember(data.mapName) { mutableStateOf("") }
    var sectorEditTag by remember(data.mapName) { mutableStateOf("") }
    var sectorEditError by remember(data.mapName) { mutableStateOf<String?>(null) }

    val usedVertexIndices = remember(data) {
        data.lines.asSequence()
            .flatMap { sequenceOf(it.startVertex, it.endVertex) }
            .distinct()
            .toList()
    }
    val usedVertices = remember(data, usedVertexIndices) {
        usedVertexIndices.mapNotNull(data.vertices::getOrNull)
    }
    val geometryIndex = remember(data) {
        WadMapGeometryIndex.build(data)
    }
    val selectedSectorBoundaryLines = remember(geometryIndex, selectedSectorIndex) {
        selectedSectorIndex?.let {
            geometryIndex.sectorBoundaryLineIndices.getOrNull(it)
        }.orEmpty()
    }

    if (usedVertices.isEmpty()) {
        Text(
            "El mapa no contiene vértices conectados a líneas.",
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { showThings = !showThings }) {
            Text(if (showThings) "Ocultar objetos" else "Mostrar objetos")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(userZoom * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    userZoom = 1f
                    userPan = Offset.Zero
                    selectedLineIndex = null
                    selectedThingIndex = null
                    selectedSectorIndex = null
                    showElementDetails = false
                    showSectorPicker = false
                    showSectorDetails = false
                    showSectorEditor = false
                },
                enabled = userZoom != 1f || userPan != Offset.Zero ||
                    selectedLineIndex != null || selectedThingIndex != null ||
                    selectedSectorIndex != null
            ) {
                Text("Restablecer")
            }
        }
    }

    if (data.sectors.isNotEmpty()) {
        TextButton(
            onClick = { showSectorPicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Explorar sectores (${data.sectors.size})")
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clipToBounds()
            .pointerInput(data.mapName) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    userZoom = (userZoom * zoomChange).coerceIn(1f, 12f)
                    userPan = userPan + pan
                }
            }
            .pointerInput(data, geometryIndex, userZoom, userPan, showThings) {
                detectTapGestures { tap ->
                    val minX = usedVertices.minOf { it.x }.toFloat()
                    val maxX = usedVertices.maxOf { it.x }.toFloat()
                    val minY = usedVertices.minOf { it.y }.toFloat()
                    val maxY = usedVertices.maxOf { it.y }.toFloat()
                    val spanX = (maxX - minX).coerceAtLeast(1f)
                    val spanY = (maxY - minY).coerceAtLeast(1f)
                    val padding = 18f * density
                    val availableWidth = (size.width - padding * 2).coerceAtLeast(1f)
                    val availableHeight = (size.height - padding * 2).coerceAtLeast(1f)
                    val scale = minOf(availableWidth / spanX, availableHeight / spanY)
                    val contentWidth = spanX * scale
                    val contentHeight = spanY * scale
                    val left = (size.width - contentWidth) / 2f
                    val top = (size.height - contentHeight) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    fun project(x: Int, y: Int): Offset {
                        val fitted = Offset(
                            left + (x - minX) * scale,
                            top + (maxY - y) * scale
                        )
                        return center + (fitted - center) * userZoom + userPan
                    }

                    fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val lengthSquared = dx * dx + dy * dy
                        if (lengthSquared <= 0.001f) return (point - a).getDistance()
                        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) /
                            lengthSquared).coerceIn(0f, 1f)
                        val closest = Offset(a.x + t * dx, a.y + t * dy)
                        return (point - closest).getDistance()
                    }

                    val nearestThing = if (showThings) {
                        data.things.indices
                            .map { index -> index to (tap - project(data.things[index].x, data.things[index].y)).getDistance() }
                            .filter { it.second <= 22f * density }
                            .minByOrNull { it.second }
                    } else null

                    if (nearestThing != null) {
                        selectedThingIndex = nearestThing.first
                        selectedLineIndex = null
                        selectedSectorIndex = null
                        showSectorDetails = false
                        showElementDetails = true
                    } else {
                        val nearestLine = data.lines.indices
                            .mapNotNull { index ->
                                val line = data.lines[index]
                                val a = data.vertices.getOrNull(line.startVertex) ?: return@mapNotNull null
                                val b = data.vertices.getOrNull(line.endVertex) ?: return@mapNotNull null
                                index to distanceToSegment(tap, project(a.x, a.y), project(b.x, b.y))
                            }
                            .filter { it.second <= 18f * density }
                            .minByOrNull { it.second }

                        if (nearestLine != null) {
                            selectedLineIndex = nearestLine.first
                            selectedThingIndex = null
                            selectedSectorIndex = null
                            showSectorDetails = false
                            showElementDetails = true
                        } else {
                            // Undo fit + zoom + pan to convert this screen tap
                            // back into the original Doom map coordinate system.
                            val fittedTap = Offset(
                                center.x + (tap.x - center.x - userPan.x) / userZoom,
                                center.y + (tap.y - center.y - userPan.y) / userZoom
                            )
                            val insideMapBounds =
                                fittedTap.x >= left && fittedTap.x <= left + contentWidth &&
                                fittedTap.y >= top && fittedTap.y <= top + contentHeight

                            val hitSector = if (insideMapBounds) {
                                val mapX = minX + (fittedTap.x - left) / scale
                                val mapY = maxY - (fittedTap.y - top) / scale
                                geometryIndex.findSectorAtPoint(data, mapX, mapY)
                            } else {
                                null
                            }

                            if (hitSector != null) {
                                selectedSectorIndex = hitSector
                                selectedLineIndex = null
                                selectedThingIndex = null
                                showElementDetails = false
                                showSectorDetails = true
                            }
                        }
                    }
                }
            }
    ) {
        drawRect(color = backgroundColor)

        val minX = usedVertices.minOf { it.x }.toFloat()
        val maxX = usedVertices.maxOf { it.x }.toFloat()
        val minY = usedVertices.minOf { it.y }.toFloat()
        val maxY = usedVertices.maxOf { it.y }.toFloat()
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val padding = 18.dp.toPx()
        val availableWidth = (size.width - padding * 2).coerceAtLeast(1f)
        val availableHeight = (size.height - padding * 2).coerceAtLeast(1f)
        val scale = minOf(availableWidth / spanX, availableHeight / spanY)
        val contentWidth = spanX * scale
        val contentHeight = spanY * scale
        val left = (size.width - contentWidth) / 2f
        val top = (size.height - contentHeight) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        fun project(vertex: WadMapVertex): Offset {
            val fitted = Offset(
                x = left + (vertex.x - minX) * scale,
                y = top + (maxY - vertex.y) * scale
            )
            return center + (fitted - center) * userZoom + userPan
        }

        val lineStroke = 2.dp.toPx().coerceAtLeast(1.5f)
        data.lines.forEachIndexed { index, line ->
            val startVertex = data.vertices.getOrNull(line.startVertex) ?: return@forEachIndexed
            val endVertex = data.vertices.getOrNull(line.endVertex) ?: return@forEachIndexed
            val isSelected = selectedLineIndex == index
            val isSectorBoundary = index in selectedSectorBoundaryLines
            drawLine(
                color = when {
                    isSelected -> selectedColor
                    isSectorBoundary -> selectedColor.copy(alpha = 0.82f)
                    else -> lineColor
                },
                start = project(startVertex),
                end = project(endVertex),
                strokeWidth = when {
                    isSelected -> lineStroke * 2.5f
                    isSectorBoundary -> lineStroke * 1.9f
                    else -> lineStroke
                },
                cap = StrokeCap.Round
            )
        }

        if (showThings) {
            val thingRadius = 1.6.dp.toPx().coerceAtLeast(1f)
            data.things.forEachIndexed { index, thing ->
                val isSelected = selectedThingIndex == index
                drawCircle(
                    color = if (isSelected) selectedColor else thingColor.copy(alpha = 0.72f),
                    radius = if (isSelected) thingRadius * 3f else thingRadius,
                    center = project(WadMapVertex(thing.x, thing.y))
                )
            }
        }
    }

    val selectedThing = selectedThingIndex?.let(data.things::getOrNull)
    val selectedLine = selectedLineIndex?.let(data.lines::getOrNull)
    val selectedLineSectors = remember(selectedLine, data.sideDefs, data.sectors) {
        selectedLine?.let { line ->
            listOf(line.rightSide, line.leftSide)
                .filter { it != 0xffff }
                .mapNotNull { sideIndex -> data.sideDefs.getOrNull(sideIndex)?.sectorIndex }
                .distinct()
                .mapNotNull { sectorIndex ->
                    data.sectors.getOrNull(sectorIndex)?.let { sectorIndex to it }
                }
        }.orEmpty()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "━ Geometría",
            style = MaterialTheme.typography.labelSmall,
            color = lineColor
        )
        if (showThings) {
            Text(
                "● Objetos",
                style = MaterialTheme.typography.labelSmall,
                color = thingColor
            )
        }
        Text(
            "Toca elementos · Pellizca para zoom · Arrastra para mover",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showElementDetails && (selectedThing != null || selectedLine != null)) {
        AlertDialog(
            onDismissRequest = { showElementDetails = false },
            title = {
                Text(if (selectedThing != null) "Propiedades del objeto" else "Propiedades de la línea")
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("Mapa: ${data.mapName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedThing != null) {
                        val thingFlags = WadMapLabels.thingFlags(selectedThing.flags)
                        Text(
                            WadMapLabels.thingName(selectedThing.type),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("DoomEd: #${selectedThing.type} · ${WadMapLabels.thingCategory(selectedThing.type)}")
                        Text("Posición X: ${selectedThing.x}")
                        Text("Posición Y: ${selectedThing.y}")
                        Text("Orientación: ${selectedThing.angle}°")
                        HorizontalDivider()
                        Text("Flags: ${selectedThing.flags} (0x${selectedThing.flags.toString(16).uppercase().padStart(4, '0')})", fontWeight = FontWeight.SemiBold)
                        thingFlags.labels.forEach { label ->
                            Text("• $label")
                        }
                        if (thingFlags.unknownMask != 0) {
                            Text(
                                WadMapLabels.unknownFlags(thingFlags.unknownMask),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (selectedLine != null) {
                        val lineFlags = WadMapLabels.lineFlags(selectedLine.flags)
                        Text("Vértice inicial: ${selectedLine.startVertex}")
                        Text("Vértice final: ${selectedLine.endVertex}")
                        Text(
                            "Flags: ${selectedLine.flags} (0x${selectedLine.flags.toString(16).uppercase().padStart(4, '0')})",
                            fontWeight = FontWeight.SemiBold
                        )
                        lineFlags.labels.forEach { label ->
                            Text("• $label")
                        }
                        if (lineFlags.labels.isEmpty()) {
                            Text("Sin flags de línea activados.")
                        }
                        if (lineFlags.unknownMask != 0) {
                            Text(
                                WadMapLabels.unknownFlags(lineFlags.unknownMask),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text("Especial: ${selectedLine.special}")
                        Text("Tag: ${selectedLine.tag}")
                        Text("Lado frontal: ${if (selectedLine.rightSide == 0xffff) "Ninguno" else selectedLine.rightSide}")
                        Text("Lado posterior: ${if (selectedLine.leftSide == 0xffff) "Ninguno" else selectedLine.leftSide}")
                        if (selectedLineSectors.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Sectores adyacentes", fontWeight = FontWeight.SemiBold)
                            selectedLineSectors.forEach { (sectorIndex, sector) ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Sector #$sectorIndex", fontWeight = FontWeight.SemiBold)
                                    Text("Suelo: ${sector.floorHeight} · ${sector.floorTexture}")
                                    Text("Techo: ${sector.ceilingHeight} · ${sector.ceilingTexture}")
                                    Text("Iluminación: ${sector.lightLevel}")
                                    Text("Especial: ${WadMapLabels.sectorSpecialName(sector.special)} · Tag: ${sector.tag}")
                                    TextButton(
                                        onClick = {
                                            selectedSectorIndex = sectorIndex
                                            showElementDetails = false
                                            showSectorDetails = true
                                        }
                                    ) {
                                        Text("Inspeccionar sector #$sectorIndex")
                                    }
                                }
                            }
                        } else {
                            Text("No se pudieron resolver los sectores adyacentes.")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showElementDetails = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (showSectorPicker) {
        AlertDialog(
            onDismissRequest = { showSectorPicker = false },
            title = { Text("Sectores de ${data.mapName}") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                ) {
                    items(data.sectors.indices.toList()) { sectorIndex ->
                        val sector = data.sectors[sectorIndex]
                        TextButton(
                            onClick = {
                                selectedSectorIndex = sectorIndex
                                showSectorPicker = false
                                showSectorDetails = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("Sector #$sectorIndex", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Suelo ${sector.floorHeight} · Techo ${sector.ceilingHeight} · Luz ${sector.lightLevel}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${sector.floorTexture} / ${sector.ceilingTexture}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSectorPicker = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    val selectedSector = selectedSectorIndex?.let(data.sectors::getOrNull)
    if (showSectorEditor && selectedSector != null && selectedSectorIndex != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isSavingMap) {
                    showSectorEditor = false
                    showSectorDetails = true
                }
            },
            title = { Text("Editar sector #$selectedSectorIndex") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Los cambios se guardarán en una copia nueva del WAD. El archivo original no se modificará.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = sectorEditFloorHeight,
                        onValueChange = { sectorEditFloorHeight = it; sectorEditError = null },
                        label = { Text("Altura del suelo") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditCeilingHeight,
                        onValueChange = { sectorEditCeilingHeight = it; sectorEditError = null },
                        label = { Text("Altura del techo") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditFloorTexture,
                        onValueChange = {
                            sectorEditFloorTexture = it.uppercase(Locale.ROOT)
                            sectorEditError = null
                        },
                        label = { Text("Textura del suelo (máximo 8 caracteres)") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditCeilingTexture,
                        onValueChange = {
                            sectorEditCeilingTexture = it.uppercase(Locale.ROOT)
                            sectorEditError = null
                        },
                        label = { Text("Textura del techo (máximo 8 caracteres)") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditLightLevel,
                        onValueChange = { sectorEditLightLevel = it; sectorEditError = null },
                        label = { Text("Iluminación (0–255)") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditSpecial,
                        onValueChange = { sectorEditSpecial = it; sectorEditError = null },
                        label = { Text("Especial (0–65535)") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sectorEditTag,
                        onValueChange = { sectorEditTag = it; sectorEditError = null },
                        label = { Text("Tag (0–65535)") },
                        singleLine = true,
                        enabled = !isSavingMap,
                        modifier = Modifier.fillMaxWidth()
                    )
                    sectorEditError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (isSavingMap) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Creando y verificando la copia…")
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showSectorEditor = false
                            showSectorDetails = true
                        },
                        enabled = !isSavingMap
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        enabled = !isSavingMap,
                        onClick = {
                            try {
                                val sectorIndex = selectedSectorIndex
                                    ?: throw IllegalStateException("No hay un sector seleccionado.")
                                val updated = selectedSector.copy(
                                    floorHeight = sectorEditFloorHeight.toIntOrNull()
                                        ?: throw IllegalArgumentException("La altura del suelo debe ser un número entero."),
                                    ceilingHeight = sectorEditCeilingHeight.toIntOrNull()
                                        ?: throw IllegalArgumentException("La altura del techo debe ser un número entero."),
                                    floorTexture = sectorEditFloorTexture.trim(),
                                    ceilingTexture = sectorEditCeilingTexture.trim(),
                                    lightLevel = sectorEditLightLevel.toIntOrNull()
                                        ?: throw IllegalArgumentException("La iluminación debe ser un número entero."),
                                    special = sectorEditSpecial.toIntOrNull()
                                        ?: throw IllegalArgumentException("El especial debe ser un número entero."),
                                    tag = sectorEditTag.toIntOrNull()
                                        ?: throw IllegalArgumentException("El tag debe ser un número entero.")
                                )
                                WadSectorCodec.encode(updated)
                                onSaveSector(sectorIndex, updated)
                                showSectorEditor = false
                                showSectorDetails = false
                            } catch (error: Exception) {
                                sectorEditError = error.localizedMessage
                                    ?: "Revisa los valores introducidos."
                            }
                        }
                    ) {
                        Text("Guardar copia")
                    }
                }
            }
        )
    }

    if (showSectorDetails && selectedSector != null) {
        AlertDialog(
            onDismissRequest = { showSectorDetails = false },
            title = { Text("Propiedades del sector #$selectedSectorIndex") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Mapa: ${data.mapName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Altura del suelo: ${selectedSector.floorHeight}")
                    Text("Textura del suelo: ${selectedSector.floorTexture}")
                    Text("Altura del techo: ${selectedSector.ceilingHeight}")
                    Text("Textura del techo: ${selectedSector.ceilingTexture}")
                    Text("Iluminación: ${selectedSector.lightLevel} / 255")
                    Text("Especial: ${WadMapLabels.sectorSpecialName(selectedSector.special)} (${selectedSector.special})")
                    Text("Tag: ${selectedSector.tag}")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            sectorEditFloorHeight = selectedSector.floorHeight.toString()
                            sectorEditCeilingHeight = selectedSector.ceilingHeight.toString()
                            sectorEditFloorTexture = selectedSector.floorTexture
                            sectorEditCeilingTexture = selectedSector.ceilingTexture
                            sectorEditLightLevel = selectedSector.lightLevel.toString()
                            sectorEditSpecial = selectedSector.special.toString()
                            sectorEditTag = selectedSector.tag.toString()
                            sectorEditError = null
                            showSectorDetails = false
                            showSectorEditor = true
                        },
                        enabled = !isSavingMap
                    ) {
                        Text("Editar")
                    }
                    TextButton(onClick = { showSectorDetails = false }) {
                        Text("Cerrar")
                    }
                }
            }
        )
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
        "P_START", "PP_START", "P_END", "PP_END",
        "T_START", "T_END", "TT_START", "TT_END"
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
            "T_START", "TT_START" -> section = "Textura PNG"
            "T_END", "TT_END" -> if (section == "Textura PNG") section = null
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
        "Gráfico / sprite", "Textura de piso", "Parche gráfico", "Textura PNG" -> "🖼️"
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

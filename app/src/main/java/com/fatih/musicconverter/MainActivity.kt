package com.fatih.musicconverter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.fatih.musicconverter.model.MusicFile
import com.fatih.musicconverter.model.MusicMetadata
import com.fatih.musicconverter.ui.theme.MusicConverterTheme
import com.fatih.musicconverter.utils.MetadataParser
import com.fatih.musicconverter.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random
import android.os.Vibrator
import android.os.VibrationEffect
import android.media.ToneGenerator
import android.media.AudioManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MusicConverterTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

fun sanitizeFileName(name: String): String {
    val turkishMap = mapOf(
        'ı' to 'i', 'İ' to 'I', 'ğ' to 'g', 'Ğ' to 'G',
        'ü' to 'u', 'Ü' to 'U', 'ş' to 's', 'Ş' to 'S',
        'ö' to 'o', 'Ö' to 'O', 'ç' to 'c', 'Ç' to 'C'
    )
    var result = name
    turkishMap.forEach { (k, v) -> result = result.replace(k, v) }
    result = result.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
    if (result.length > 80) result = result.take(80)
    return result
}

suspend fun convertAudioWithResult(context: Context, inputPath: String, outputFinalPath: String, format: String, bitrate: String): Result<Boolean> = withContext(Dispatchers.IO) {
    var tempInputFile: File? = null
    var tempOutputFile: File? = null
    return@withContext try {
        val originInput = File(inputPath)
        if (!originInput.exists()) return@withContext Result.failure(Exception("Giriş dosyası bulunamadı"))
        
        val workDir = File(context.filesDir, "work").apply { mkdirs() }
        tempInputFile = File(workDir, "temp_in.${originInput.extension}")
        tempOutputFile = File(workDir, "temp_out.$format")
        
        tempInputFile.delete()
        tempOutputFile.delete()
        originInput.copyTo(tempInputFile, overwrite = true)
        
        val cmd = when (format) {
            "mp3" -> "-i \"${tempInputFile.absolutePath}\" -c:a libmp3lame -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
            "aac" -> "-i \"${tempInputFile.absolutePath}\" -c:a aac -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
            "wav" -> "-i \"${tempInputFile.absolutePath}\" -c:a pcm_s16le -y \"${tempOutputFile.absolutePath}\""
            "flac" -> "-i \"${tempInputFile.absolutePath}\" -c:a flac -y \"${tempOutputFile.absolutePath}\""
            else -> "-i \"${tempInputFile.absolutePath}\" -c:a libmp3lame -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
        }
        
        val session = FFmpegKit.execute(cmd)
        if (ReturnCode.isSuccess(session.returnCode)) {
            if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                val finalFile = File(outputFinalPath)
                finalFile.parentFile?.mkdirs()
                tempOutputFile.copyTo(finalFile, overwrite = true)
                Result.success(true)
            } else Result.failure(Exception("Çıktı dosyası oluşturulamadı"))
        } else if (ReturnCode.isCancel(session.returnCode)) {
            Result.success(false)
        } else {
            Result.failure(Exception("FFmpeg Hatası"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        tempInputFile?.delete()
        tempOutputFile?.delete()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedFiles by remember { mutableStateOf(emptyList<MusicFile>()) }
    var editingFile by remember { mutableStateOf<MusicFile?>(null) }
    
    var targetFormat by remember { mutableStateOf("mp3") }
    var targetQuality by remember { mutableStateOf("192k") }
    var formatExpanded by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    
    val internalDir = remember { File(context.filesDir, "Converted").apply { mkdirs() } }
    var outputDirectoryUri by remember { mutableStateOf<Uri?>(null) }

    var isConverting by remember { mutableStateOf(false) }
    var currentFile by remember { mutableStateOf("") }
    var completedCount by remember { mutableStateOf(0) }
    var conversionJob by remember { mutableStateOf<Job?>(null) }
    
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }

    val notificationHelper = remember { NotificationHelper(context) }
    
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    
    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun playSuccessFeedback() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else vibrator.vibrate(200)
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (e: Exception) { }
    }    

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            try {
                getFileFromUri(context, uri)?.let { file ->
                    val metadata = MetadataParser.parseMetadata(context, file.absolutePath)
                    selectedFiles = selectedFiles + MusicFile(
                        id = UUID.randomUUID().toString(),
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        format = file.extension,
                        metadata = metadata
                    )
                }
            } catch (e: Exception) { }
        }
    }

    val directoryPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            outputDirectoryUri = it
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putString("output_directory_uri", it.toString()).apply()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)

        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPrefs.getString("output_directory_uri", null)?.let {
            val uri = Uri.parse(it)
            if (context.contentResolver.persistedUriPermissions.any { p -> p.uri == uri && p.isReadPermission }) {
                outputDirectoryUri = uri
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.ExtraBold) },
                actions = {
                    if (selectedFiles.isNotEmpty() && !isConverting) {
                        IconButton(onClick = { selectedFiles = emptyList(); selectedIds = emptySet() }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isConverting) {
                FloatingActionButton(onClick = { filePickerLauncher.launch("audio/*") }, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(32.dp))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background))
        )) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.output_directory), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            val path = outputDirectoryUri?.let { DocumentFile.fromTreeUri(context, it)?.uri?.path } ?: internalDir.absolutePath
                            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(onClick = { formatExpanded = true }, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                                Text(targetFormat.uppercase(), style = MaterialTheme.typography.labelMedium)
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                                listOf("mp3", "aac", "wav", "flac").forEach { fmt ->
                                    DropdownMenuItem(text = { Text(fmt.uppercase()) }, onClick = { targetFormat = fmt; formatExpanded = false })
                                }
                            }
                        }
                        if (targetFormat == "mp3" || targetFormat == "aac") {
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(onClick = { qualityExpanded = true }, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                                    Text(targetQuality, style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                                    listOf("128k", "192k", "256k", "320k").forEach { q ->
                                        DropdownMenuItem(text = { Text(q) }, onClick = { targetQuality = q; qualityExpanded = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.add_music_files), color = Color.Gray)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (selectedIds.size == selectedFiles.size) selectedIds = emptySet()
                        else selectedIds = selectedFiles.map { it.id }.toSet()
                    }) {
                        Icon(if (selectedIds.size == selectedFiles.size) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedIds.size == selectedFiles.size) stringResource(R.string.deselect_all) else stringResource(R.string.select_all), style = MaterialTheme.typography.labelMedium)
                    }
                    if (selectedIds.isNotEmpty() && !isConverting) {
                        Button(onClick = { showBatchEditDialog = true }, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(32.dp)) {
                            Text(stringResource(R.string.batch_edit), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (isConverting) {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${stringResource(R.string.converting)}: $currentFile", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            Text("$completedCount / ${selectedFiles.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { MusicTableHeader() }
                    items(selectedFiles) { music ->
                        MusicRow(
                            music = music,
                            isSelected = selectedIds.contains(music.id),
                            onSelect = { 
                                if (selectedIds.contains(music.id)) selectedIds = selectedIds - music.id
                                else selectedIds = selectedIds + music.id
                            },
                            onEdit = { m -> if (!isConverting) editingFile = m },
                            onDelete = { m -> if (!isConverting) selectedFiles = selectedFiles.filter { it.id != m.id } },
                            onShare = { m ->
                                val sanitizedName = sanitizeFileName(if (!m.metadata.title.isNullOrBlank() && !m.metadata.artist.isNullOrBlank()) "${m.metadata.artist} - ${m.metadata.title}" else m.name.substringBeforeLast(".")) + "." + targetFormat
                                val file = File(internalDir, sanitizedName)
                                if (file.exists()) shareFile(file)
                            }
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isConverting) {
                            conversionJob?.cancel()
                            isConverting = false
                            FFmpegKit.cancel()
                        } else {
                            isConverting = true
                            completedCount = 0
                            conversionJob = scope.launch {
                                for (music in selectedFiles) {
                                    if (!isConverting) break
                                    withContext(Dispatchers.Main) { 
                                        currentFile = music.metadata.title ?: music.name
                                        notificationHelper.showProgressNotification(currentFile, 0)
                                    }
                                    val meta = music.metadata
                                    val nameBase = if (!meta.title.isNullOrBlank() && !meta.artist.isNullOrBlank()) "${meta.artist} - ${meta.title}" else music.name.substringBeforeLast(".")
                                    val sanitized = sanitizeFileName(nameBase) + "." + targetFormat
                                    val outputFile = File(internalDir, sanitized)
                                    val result = convertAudioWithResult(context, music.path, outputFile.absolutePath, targetFormat, targetQuality)
                                    withContext(Dispatchers.Main) {
                                        result.onSuccess { success ->
                                            if (success) {
                                                completedCount++
                                                val outputDir = outputDirectoryUri?.let { DocumentFile.fromTreeUri(context, it) }
                                                if (outputDir != null) {
                                                    outputDir.createFile("audio/$targetFormat", sanitized)?.let { df ->
                                                        context.contentResolver.openOutputStream(df.uri)?.use { os -> outputFile.inputStream().use { it.copyTo(os) } }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                isConverting = false
                                if (completedCount == selectedFiles.size) {
                                    playSuccessFeedback(); showConfetti = true
                                    launch { delay(4000); showConfetti = false }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp),
                    enabled = selectedFiles.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isConverting) Color.Red else MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isConverting) stringResource(R.string.cancel_conversion) else "${selectedFiles.size} ${stringResource(R.string.convert)}")
                }
            }
        }
    }

    if (editingFile != null) {
        EditMetadataDialog(editingFile!!, { editingFile = null }) { meta ->
            selectedFiles = selectedFiles.map { if (it.id == editingFile!!.id) it.copy(metadata = meta) else it }
            editingFile = null
        }
    }

    if (showBatchEditDialog) {
        BatchEditMetadataDialog({ showBatchEditDialog = false }) { artist, track, album, year ->
            selectedFiles = selectedFiles.map { if (selectedIds.contains(it.id)) it.copy(metadata = it.metadata.copy(artist = if (artist.isBlank()) it.metadata.artist else artist, trackNumber = if (track.isBlank()) it.metadata.trackNumber else track, album = if (album.isBlank()) it.metadata.album else album, year = if (year.isBlank()) it.metadata.year else year)) else it }
            showBatchEditDialog = false; selectedIds = emptySet()
        }
    }

    if (showConfetti) ConfettiOverlay()
}

@Composable
fun MusicTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Spacer(modifier = Modifier.width(36.dp))
        Text("#", modifier = Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text("ŞARKI / SANATÇI", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MusicRow(music: MusicFile, isSelected: Boolean, onSelect: () -> Unit, onEdit: (MusicFile) -> Unit, onDelete: (MusicFile) -> Unit, onShare: (MusicFile) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onSelect() }, color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isSelected, onCheckedChange = { onSelect() }, modifier = Modifier.size(40.dp))
            Text(music.metadata.trackNumber ?: "--", modifier = Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (music.metadata.coverUri != null) AsyncImage(model = music.metadata.coverUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(music.metadata.title ?: music.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(music.metadata.artist ?: "-", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row {
                IconButton(onClick = { onShare(music) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp)) }
                IconButton(onClick = { onEdit(music) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp)) }
                IconButton(onClick = { onDelete(music) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

fun getFileFromUri(context: Context, uri: Uri): File? {
    return try {
        var name = "audio"; var ext = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val displayName = cursor.getString(idx)
                if (displayName != null) {
                    name = displayName.substringBeforeLast("."); ext = "." + displayName.substringAfterLast(".", "")
                }
            }
        }
        val file = File(context.cacheDir, sanitizeFileName(name) + ext)
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
        file
    } catch (e: Exception) { null }
}

@Composable
fun EditMetadataDialog(music: MusicFile, onDismiss: () -> Unit, onSave: (MusicMetadata) -> Unit) {
    var title by remember { mutableStateOf(music.metadata.title ?: "") }
    var artist by remember { mutableStateOf(music.metadata.artist ?: "") }
    var track by remember { mutableStateOf(music.metadata.trackNumber ?: "") }
    var album by remember { mutableStateOf(music.metadata.album ?: "") }
    var year by remember { mutableStateOf(music.metadata.year ?: "") }
    var cover by remember { mutableStateOf(music.metadata.coverUri) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { cover = it?.toString() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray).clickable { imagePicker.launch("image/*") }, contentAlignment = Alignment.Center) {
                    if (cover != null) AsyncImage(model = cover, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.Add, null, tint = Color.White)
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title)) })
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.artist)) })
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text(stringResource(R.string.album)) })
                OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text(stringResource(R.string.track_number)) })
                OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text(stringResource(R.string.year)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(music.metadata.copy(title = title, artist = artist, album = album, trackNumber = track, year = year, coverUri = cover)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
fun BatchEditMetadataDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var artist by remember { mutableStateOf("") }
    var track by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.artist)) })
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text(stringResource(R.string.album)) })
                OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text(stringResource(R.string.track_number)) })
                OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text(stringResource(R.string.year)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(artist, track, album, year) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
fun ConfettiOverlay() {
    val particles = remember { List(50) { ConfettiParticle() } }
    val progress by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)))
    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { p ->
            val y = (p.y + progress * size.height * p.speed) % size.height; val x = p.x * size.width + Math.sin(progress.toDouble() * 5 + p.offset).toFloat() * 50f
            rotate(progress * 360f * p.rotationSpeed, pivot = androidx.compose.ui.geometry.Offset(x, y)) {
                drawRect(color = p.color, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(20f, 20f))
            }
        }
    }
}

class ConfettiParticle {
    val x = Random.nextFloat(); val y = Random.nextFloat() * -1000f; val speed = 0.5f + Random.nextFloat() * 0.5f
    val rotationSpeed = if (Random.nextBoolean()) 1f else -1f; val offset = Random.nextFloat() * 10f
    val color = Color(Random.nextInt(150, 255), Random.nextInt(150, 255), Random.nextInt(150, 255), 200)
}

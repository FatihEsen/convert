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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import com.fatih.musicconverter.model.MusicFile
import com.fatih.musicconverter.model.MusicMetadata
import com.fatih.musicconverter.ui.theme.MusicConverterTheme
import com.fatih.musicconverter.utils.MetadataParser
import com.fatih.musicconverter.utils.NotificationHelper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.fatih.musicconverter.R
import android.os.Vibrator
import android.os.VibrationEffect
import android.media.ToneGenerator
import android.media.AudioManager
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.random.Random

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
    // Allow dots, hyphens and spaces for better readability
    result = result.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
    if (result.length > 80) result = result.take(80) // Increased limit for artist - title
    return result
}

suspend fun convertAudioWithResult(context: Context, inputPath: String, outputFinalPath: String, format: String, bitrate: String): Result<Boolean> = withContext(Dispatchers.IO) {
    var tempInputFile: File? = null
    var tempOutputFile: File? = null
    return@withContext try {
        Log.d("MC", "Starting FFmpegKit conversion: $inputPath")
        val originInput = File(inputPath)
        if (!originInput.exists()) {
            return@withContext Result.failure(Exception("Giriş dosyası bulunamadı: $inputPath"))
        }

        // Extremely safe paths in Internal Storage (filesDir)
        // Native FFmpeg has best access here
        val workDir = File(context.filesDir, "work").apply { mkdirs() }
        tempInputFile = File(workDir, "temp_in.${originInput.extension}")
        tempOutputFile = File(workDir, "temp_out.$format")

        // Clean previous temps just in case
        tempInputFile.delete()
        tempOutputFile.delete()

        // Copy file to internal work dir
        originInput.copyTo(tempInputFile, overwrite = true)
        Log.d("MC", "Copied to internal work: ${tempInputFile.absolutePath} (${tempInputFile.length()} bytes)")

        // Command with absolute paths
        val cmd = when (format) {
            "mp3" -> "-i \"${tempInputFile.absolutePath}\" -c:a libmp3lame -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
            "aac" -> "-i \"${tempInputFile.absolutePath}\" -c:a aac -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
            "wav" -> "-i \"${tempInputFile.absolutePath}\" -c:a pcm_s16le -y \"${tempOutputFile.absolutePath}\""
            "flac" -> "-i \"${tempInputFile.absolutePath}\" -c:a flac -y \"${tempOutputFile.absolutePath}\""
            else -> "-i \"${tempInputFile.absolutePath}\" -c:a libmp3lame -b:a $bitrate -y \"${tempOutputFile.absolutePath}\""
        }
        
        Log.d("MC", "FFmpegKit CMD: $cmd")
        
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        val logs = session.logsAsString
        val failStackTrace = session.failStackTrace
        
        Log.d("MC", "FFmpegKit Logs: $logs")
        if (failStackTrace != null) Log.e("MC", "FFmpegKit StackTrace: $failStackTrace")

        if (ReturnCode.isSuccess(returnCode)) {
            if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                val finalFile = File(outputFinalPath)
                finalFile.parentFile?.mkdirs()
                tempOutputFile.copyTo(finalFile, overwrite = true)
                Log.d("MC", "Successfully moved result to: $outputFinalPath")
                Result.success(true)
            } else {
                Result.failure(Exception("Çıktı dosyası boş veya oluşturulamadı"))
            }
        } else if (ReturnCode.isCancel(returnCode)) {
            Log.d("MC", "FFmpegKit Cancelled")
            Result.success(false)
        } else {
            val errorInfo = if (logs.length > 300) logs.takeLast(300) else logs
            Result.failure(Exception("FFmpeg Hatası:\n$errorInfo"))
        }
    } catch (e: Exception) {
        Log.e("MC", "FFmpegKit Thread Exception", e)
        Result.failure(e)
    } finally {
        try {
            tempInputFile?.delete()
            tempOutputFile?.delete()
        } catch (e: Exception) {
            Log.e("MC", "Cleanup error", e)
        }
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
            } else {
                vibrator.vibrate(200)
            }
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 200)
        } catch (e: Exception) {
            Log.e("MC", "Feedback error", e)
        }
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
            } catch (e: Exception) {
                Log.e("MC", "Add file error", e)
            }
        }
    }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            outputDirectoryUri = it
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putString("output_directory_uri", it.toString()).apply()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val uriString = sharedPrefs.getString("output_directory_uri", null)
        uriString?.let {
            val uri = Uri.parse(it)
            if (context.contentResolver.persistedUriPermissions.any { p -> p.uri == uri && p.isReadPermission }) {
                outputDirectoryUri = uri
            } else {
                sharedPrefs.edit().remove("output_directory_uri").apply()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.app_name), 
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                actions = {
                    if (selectedFiles.isNotEmpty() && !isConverting) {
                        IconButton(onClick = { selectedFiles = emptyList() }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            if (!isConverting) {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("audio/*") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(32.dp))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)
            )
        )) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.output_directory), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    val path = outputDirectoryUri?.let { DocumentFile.fromTreeUri(context, it)?.uri?.path } ?: internalDir.absolutePath
                    Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Button(onClick = { directoryPickerLauncher.launch(null) }) {
                        Text(stringResource(R.string.select_directory))
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.format), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Box {
                                OutlinedButton(onClick = { formatExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = !isConverting) {
                                    Text(targetFormat.uppercase())
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = formatExpanded, onDismissRequest = { formatExpanded = false }) {
                                    listOf("mp3", "aac", "wav", "flac").forEach { fmt ->
                                        DropdownMenuItem(text = { Text(fmt.uppercase()) }, onClick = { targetFormat = fmt; formatExpanded = false })
                                    }
                                }
                            }
                        }
                        
                        if (targetFormat == "mp3" || targetFormat == "aac") {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Box {
                                    OutlinedButton(onClick = { qualityExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = !isConverting) {
                                        Text(targetQuality)
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
            }

            if (selectedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.add_music_files), color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                // Bulk Selection Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (selectedIds.size == selectedFiles.size) selectedIds = emptySet()
                        else selectedIds = selectedFiles.map { it.id }.toSet()
                    }) {
                        Icon(if (selectedIds.size == selectedFiles.size) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedIds.size == selectedFiles.size) stringResource(R.string.deselect_all) else stringResource(R.string.select_all))
                    }
                    
                    if (selectedIds.isNotEmpty() && !isConverting) {
                        Button(
                            onClick = { showBatchEditDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.batch_edit), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (isConverting) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("${stringResource(R.string.converting)}: $currentFile", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text("$completedCount / ${selectedFiles.size}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(selectedFiles) { music ->
                        MusicItemSimple(
                            music = music,
                            isSelected = selectedIds.contains(music.id),
                            onSelect = { 
                                if (selectedIds.contains(music.id)) selectedIds = selectedIds - music.id
                                else selectedIds = selectedIds + music.id
                            },
                            onEdit = { if (!isConverting) editingFile = it },
                            onDelete = { if (!isConverting) selectedFiles = selectedFiles.filter { f -> f.id != music.id } },
                            onShare = { 
                                val sanitizedName = sanitizeFileName(if (!it.metadata.title.isNullOrBlank() && !it.metadata.artist.isNullOrBlank()) "${it.metadata.artist} - ${it.metadata.title}" else it.name.substringBeforeLast(".")) + "." + targetFormat
                                val file = File(internalDir, sanitizedName)
                                if (file.exists()) shareFile(file)
                                else Toast.makeText(context, "Önce dönüştürmeniz gerekiyor", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                
                if (isConverting) {
                     Button(
                        onClick = {
                            conversionJob?.cancel()
                            isConverting = false
                            FFmpegKit.cancel()
                            notificationHelper.showCompletionNotification("İptal Edildi", false, "İşlem iptal edildi")
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Close, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cancel_conversion), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            isConverting = true
                            completedCount = 0
                            
                            conversionJob = scope.launch {
                                for (music in selectedFiles) {
                                    if (!isConverting) break
                                    try {
                                        withContext(Dispatchers.Main) {
                                            currentFile = music.metadata.title ?: music.name
                                            notificationHelper.showProgressNotification(currentFile, 0)
                                        }
                                        
                                        val meta = music.metadata
                                        val fileNameBase = if (!meta.title.isNullOrBlank() && !meta.artist.isNullOrBlank()) {
                                            val track = if (!meta.trackNumber.isNullOrBlank()) "${meta.trackNumber}." else ""
                                            "$track${meta.artist} - ${meta.title}"
                                        } else {
                                            music.name.substringBeforeLast(".")
                                        }
                                        val sanitizedName = sanitizeFileName(fileNameBase) + "." + targetFormat
                                        val outputFile = File(internalDir, sanitizedName)
                                        
                                        Log.d("MC", "Calling convertAudioWithResult for ${music.name}")
                                        val result = convertAudioWithResult(context, music.path, outputFile.absolutePath, targetFormat, targetQuality)
                                        
                                        if (!isConverting) {
                                            outputFile.delete()
                                            break
                                        }

                                        withContext(Dispatchers.Main) {
                                            result.onSuccess { success ->
                                                if (success) {
                                                    completedCount++
                                                    notificationHelper.showCompletionNotification(sanitizedName, true)
                                                    
                                                    try {
                                                        Log.d("MC", "Conversion success, copying to destination...")
                                                        val outputDir = outputDirectoryUri?.let { DocumentFile.fromTreeUri(context, it) }
                                                        if (outputDir != null) {
                                                            val destFile = outputDir.createFile("audio/$targetFormat", sanitizedName)
                                                            destFile?.let {
                                                                context.contentResolver.openOutputStream(it.uri)?.use { outputStream ->
                                                                    outputFile.inputStream().use { inputStream ->
                                                                        inputStream.copyTo(outputStream)
                                                                    }
                                                                }
                                                                Log.d("MC", "Copied to Safari/Document Tree")
                                                            }
                                                        } else {
                                                             val externalDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Converter")
                                                             if (!externalDir.exists()) externalDir.mkdirs()
                                                             val dest = File(externalDir, sanitizedName)
                                                             outputFile.copyTo(dest, true)
                                                             android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                                                             Log.d("MC", "Copied to Music/Converter via MediaScanner")
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("MC", "File copy error", e)
                                                        Toast.makeText(context, "Kopyalama hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Log.d("MC", "Conversion cancelled by user or library")
                                                }
                                            }.onFailure { e ->
                                                Log.e("MC", "Conversion failed for ${music.name}", e)
                                                notificationHelper.showCompletionNotification(music.name, false, e.message)
                                                Toast.makeText(context, "Hata (${music.name}): ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        delay(500)
                                    } catch (e: Exception) {
                                        Log.e("MC", "Exception in conversion loop", e)
                                        withContext(Dispatchers.Main) {
                                            notificationHelper.showCompletionNotification(music.name, false, e.message)
                                            Toast.makeText(context, "Beklenmedik hata: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                
                                    isConverting = false
                                    if (completedCount == selectedFiles.size) {
                                        playSuccessFeedback()
                                        showConfetti = true
                                        scope.launch {
                                            delay(4000)
                                            showConfetti = false
                                        }
                                        Toast.makeText(context, stringResource(R.string.done_all), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp),
                        enabled = !isConverting && selectedFiles.isNotEmpty(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${selectedFiles.size} ${stringResource(R.string.convert_files_to)} ${targetFormat.uppercase()}",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    if (editingFile != null) {
        EditMetadataDialog(editingFile!!, { editingFile = null }) { updatedMetadata ->
            selectedFiles = selectedFiles.map { if (it.id == editingFile!!.id) it.copy(metadata = updatedMetadata) else it }
            editingFile = null
        }
    }

    if (showBatchEditDialog) {
        BatchEditMetadataDialog(
            onDismiss = { showBatchEditDialog = false },
            onSave = { artist, track ->
                selectedFiles = selectedFiles.map { music ->
                    if (selectedIds.contains(music.id)) {
                        music.copy(metadata = music.metadata.copy(
                            artist = artist.ifBlank { music.metadata.artist },
                            trackNumber = track.ifBlank { music.metadata.trackNumber }
                        ))
                    } else music
                }
                showBatchEditDialog = false
                selectedIds = emptySet()
            }
        )
    }

    if (showConfetti) {
        ConfettiOverlay()
    }
}

@Composable
fun MusicItemSimple(music: MusicFile, isSelected: Boolean, onSelect: () -> Unit, onEdit: (MusicFile) -> Unit, onDelete: (MusicFile) -> Unit, onShare: (MusicFile) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) 
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onSelect() })
            
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (music.metadata.coverUri != null) {
                    AsyncImage(
                        model = music.metadata.coverUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote, 
                        null, 
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    music.metadata.title ?: music.name, 
                    style = MaterialTheme.typography.bodyLarge, 
                    fontWeight = FontWeight.Bold, 
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    music.metadata.artist ?: stringResource(R.string.unknown), 
                    style = MaterialTheme.typography.bodySmall, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant, 
                    maxLines = 1
                )
            }
            
            Row {
                IconButton(onClick = { onShare(music) }) { 
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary) 
                }
                IconButton(onClick = { onEdit(music) }) { 
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) 
                }
                IconButton(onClick = { onDelete(music) }) { 
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) 
                }
            }
        }
    }
}

fun getFileFromUri(context: Context, uri: Uri): File? {
    return try {
        var name = "audio"
        var ext = ""
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val displayName = cursor.getString(idx)
                if (displayName != null) {
                    name = displayName.substringBeforeLast(".")
                    val extension = displayName.substringAfterLast(".", "")
                    if (extension.isNotEmpty()) ext = ".$extension"
                }
            }
        }
        
        if (ext.isEmpty()) {
            val mime = context.contentResolver.getType(uri)
            if (mime != null) {
                when {
                    mime.contains("audio/mpeg") -> ext = ".mp3"
                    mime.contains("audio/mp4") -> ext = ".m4a"
                    mime.contains("audio/x-m4a") -> ext = ".m4a"
                    mime.contains("audio/wav") -> ext = ".wav"
                    mime.contains("audio/x-wav") -> ext = ".wav"
                    mime.contains("audio/flac") -> ext = ".flac"
                    mime.contains("audio/aac") -> ext = ".aac"
                }
            }
        }
        
        if (ext.isEmpty()) ext = ".mp3"
        
        val sanitizedName = sanitizeFileName(name) + ext
        val file = File(context.cacheDir, sanitizedName)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file
    } catch (e: Exception) { 
        Log.e("MC", "File error", e)
        null 
    }
}

@Composable
fun EditMetadataDialog(music: MusicFile, onDismiss: () -> Unit, onSave: (MusicMetadata) -> Unit) {
    var title by remember { mutableStateOf(music.metadata.title ?: "") }
    var artist by remember { mutableStateOf(music.metadata.artist ?: "") }
    var track by remember { mutableStateOf(music.metadata.trackNumber ?: "") }
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
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text(stringResource(R.string.track_number)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(music.metadata.copy(title = title, artist = artist, trackNumber = track, coverUri = cover)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
fun BatchEditMetadataDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var artist by remember { mutableStateOf("") }
    var track by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Seçili tüm dosyalara uygulanacak bilgileri girin. Boş bırakılan alanlar değişmeyecektir.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = track, onValueChange = { track = it }, label = { Text(stringResource(R.string.track_number)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(artist, track) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}

@Composable
fun ConfettiOverlay() {
    val particles = remember { List(50) { ConfettiParticle() } }
    val infiniteTransition = rememberInfiniteTransition()
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { p ->
            val y = (p.y + progress * size.height * p.speed) % size.height
            val x = p.x * size.width + Math.sin(progress.toDouble() * 5 + p.offset).toFloat() * 50f
            
            rotate(progress * 360f * p.rotationSpeed, pivot = androidx.compose.ui.geometry.Offset(x, y)) {
                drawRect(
                    color = p.color,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(20f, 20f)
                )
            }
        }
    }
}

class ConfettiParticle {
    val x = Random.nextFloat()
    val y = Random.nextFloat() * -1000f
    val speed = 0.5f + Random.nextFloat() * 0.5f
    val rotationSpeed = if (Random.nextBoolean()) 1f else -1f
    val offset = Random.nextFloat() * 10f
    val color = Color(
        red = Random.nextInt(150, 255),
        green = Random.nextInt(150, 255),
        blue = Random.nextInt(150, 255),
        alpha = 200
    )
}

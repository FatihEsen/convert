package com.fatih.musicconverter.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.fatih.musicconverter.model.MusicMetadata
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

object MetadataParser {

    /**
     * Parses actual metadata from the file using MediaMetadataRetriever.
     */
    fun parseMetadata(context: Context, filePath: String): MusicMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            
            // Get cover image
            val art = retriever.embeddedPicture
            var coverUri: String? = null
            if (art != null) {
                try {
                    val tempFile = File(context.cacheDir, "cover_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(tempFile).use { fos ->
                        fos.write(art)
                    }
                    coverUri = Uri.fromFile(tempFile).toString()
                } catch (e: Exception) {
                    Log.e("MetadataParser", "Error saving cover", e)
                }
            }

            if (title != null || artist != null) {
                MusicMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    trackNumber = trackNumber,
                    year = year,
                    coverUri = coverUri
                )
            } else {
                // Fallback to filename parsing
                parseFromFilename(File(filePath).name).copy(coverUri = coverUri)
            }
        } catch (e: Exception) {
            Log.e("MetadataParser", "Error parsing metadata", e)
            parseFromFilename(File(filePath).name)
        } finally {
            retriever.release()
        }
    }

    /**
     * Parses filename to extract metadata.
     */
    fun parseFromFilename(filename: String): MusicMetadata {
        val nameWithoutExtension = filename.substringBeforeLast(".")
        
        // Pattern: 01 - Artist - Title
        val pattern1 = Pattern.compile("^(\\d+)\\s*-\\s*(.+?)\\s*-\\s*(.+)$")
        val matcher1 = pattern1.matcher(nameWithoutExtension)
        if (matcher1.matches()) {
            return MusicMetadata(
                trackNumber = matcher1.group(1),
                artist = matcher1.group(2),
                title = matcher1.group(3)
            )
        }

        // Pattern: Artist - Title
        val pattern2 = Pattern.compile("^(.+?)\\s*-\\s*(.+)$")
        val matcher2 = pattern2.matcher(nameWithoutExtension)
        if (matcher2.matches()) {
            return MusicMetadata(
                artist = matcher2.group(1),
                title = matcher2.group(2)
            )
        }

        // Pattern: 01 Title
        val pattern3 = Pattern.compile("^(\\d+)\\s+(.+)$")
        val matcher3 = pattern3.matcher(nameWithoutExtension)
        if (matcher3.matches()) {
            return MusicMetadata(
                trackNumber = matcher3.group(1),
                title = matcher3.group(2)
            )
        }

        // Fallback: just use filename as title
        return MusicMetadata(title = nameWithoutExtension)
    }
}

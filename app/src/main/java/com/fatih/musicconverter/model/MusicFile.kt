package com.fatih.musicconverter.model

data class MusicMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: String? = null,
    val year: String? = null,
    val coverUri: String? = null
)

data class MusicFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val format: String,
    val metadata: MusicMetadata,
    val targetFormat: String = "mp3",
    val targetQuality: String = "192k"
)

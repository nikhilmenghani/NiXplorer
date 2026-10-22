package com.nixplorer.screen.main.tab.files.conversion

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uses Android's installed codecs. M4A is an audio-only MP4 container; WAV is little-endian PCM. */
@OptIn(UnstableApi::class)
internal object MediaConverter {
    private const val MAX_EXPORT_MS = 2L * 60 * 60 * 1000
    private const val DECODER_STALL_MS = 30_000L
    private const val MAX_WAV_DATA_BYTES = 0xffffffffL - 36

    suspend fun convert(
        context: Context,
        source: File,
        target: File,
        format: ConversionFormat,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) {
        require(source.isFile && source.canRead()) { "Cannot read ${source.name}" }
        require(source.canonicalFile != target.canonicalFile) { "Conversion must create a new file" }
        when (format) {
            ConversionFormat.MP4, ConversionFormat.M4A -> exportMp4(context, source, target, format, cancelled, onProgress)
            ConversionFormat.WAV -> decodeWav(source, target, cancelled, onProgress)
            else -> error("Unsupported media output format")
        }
    }

    private suspend fun exportMp4(
        context: Context,
        source: File,
        target: File,
        format: ConversionFormat,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.Main.immediate) {
        val coroutine = currentCoroutineContext()
        fun checkCancelled() {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Conversion cancelled")
        }
        checkCancelled()
        val completion = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(context.applicationContext)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            // Keep the advertised codecs. Unsupported input/device combinations receive an error.
            .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(exportResult)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    completion.completeExceptionally(IOException(
                        "This media could not be converted with this device's codecs (${exportException.errorCodeName}). " +
                            "The input codec or media may be unsupported or damaged", exportException
                    ))
                }
            }).build()
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setRemoveVideo(format == ConversionFormat.M4A).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(editedItem).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        val started = SystemClock.elapsedRealtime()
        val progress = ProgressHolder()
        try {
            onProgress(0f)
            transformer.start(composition, target.absolutePath)
            while (!completion.isCompleted) {
                checkCancelled()
                if (SystemClock.elapsedRealtime() - started > MAX_EXPORT_MS) throw IOException("Media conversion exceeded the 2 hour time limit")
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((progress.progress / 100f).coerceIn(0f, .99f))
                }
                delay(200)
            }
            val result = completion.await()
            checkCancelled()
            if (format == ConversionFormat.M4A) {
                require(result.audioMimeType == MimeTypes.AUDIO_AAC && result.videoMimeType == null) {
                    "No supported audio track was found in this file"
                }
            } else {
                require(result.videoMimeType == MimeTypes.VIDEO_H264) { "No supported video track was found in this file" }
                require(result.audioMimeType == null || result.audioMimeType == MimeTypes.AUDIO_AAC) { "The audio could not be converted to AAC" }
            }
            require(target.length() > 0) { "The media converter produced an empty file" }
            onProgress(1f)
        } finally {
            // Called on the same application looper, including cancellation and exceptional exits.
            transformer.cancel()
            completion.cancel()
        }
    }

    private suspend fun decodeWav(
        source: File,
        target: File,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        fun checkCancelled() {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Conversion cancelled")
        }
        checkCancelled()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        val started = SystemClock.elapsedRealtime()
        try {
            try { extractor.setDataSource(source.absolutePath) }
            catch (error: Exception) { throw IOException("This device cannot read this media container", error) }
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track was found in this file")
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            val initialRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val initialChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            onProgress(0f)
            RandomAccessFile(target, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(44))
                val pcm = PcmWriter(output, initialRate, initialChannels)
                fun progress(presentationTimeUs: Long) {
                    if (durationUs > 0) onProgress((presentationTimeUs.toFloat() / durationUs).coerceIn(0f, .99f))
                }
                if (mime == "audio/raw") {
                    pcm.configure(inputFormat)
                    val buffer = ByteBuffer.allocateDirect(1024 * 1024)
                    while (true) {
                        checkCancelled()
                        require(SystemClock.elapsedRealtime() - started < MAX_EXPORT_MS) { "Media conversion exceeded the 2 hour time limit" }
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) break
                        require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio cannot be converted" }
                        buffer.position(0); buffer.limit(count)
                        pcm.write(buffer)
                        progress(extractor.sampleTime)
                        extractor.advance()
                    }
                } else {
                    // Most decoders honor this request; floating-point output is also converted below.
                    inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    val codec = try { MediaCodec.createDecoderByType(mime) }
                    catch (error: Exception) { throw IOException("This device has no decoder for $mime", error) }
                    decoder = codec
                    try { codec.configure(inputFormat, null, null, 0); codec.start(); decoderStarted = true }
                    catch (error: Exception) { throw IOException("This device cannot decode the audio track ($mime)", error) }
                    val info = MediaCodec.BufferInfo()
                    var inputEnded = false
                    var outputEnded = false
                    var lastActivity = SystemClock.elapsedRealtime()
                    while (!outputEnded) {
                        checkCancelled()
                        val now = SystemClock.elapsedRealtime()
                        require(now - started < MAX_EXPORT_MS) { "Media conversion exceeded the 2 hour time limit" }
                        if (now - lastActivity > DECODER_STALL_MS) throw IOException("The audio decoder stopped responding")
                        if (!inputEnded) {
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                                val count = extractor.readSampleData(buffer, 0)
                                if (count < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEnded = true
                                } else {
                                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio cannot be converted" }
                                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                                    codec.queueInputBuffer(index, 0, count, extractor.sampleTime.coerceAtLeast(0), flags)
                                    extractor.advance()
                                }
                                lastActivity = SystemClock.elapsedRealtime()
                            }
                        }
                        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { pcm.configure(codec.outputFormat); lastActivity = SystemClock.elapsedRealtime() }
                            MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                        pcm.write(buffer)
                                        progress(info.presentationTimeUs)
                                    }
                                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                } finally { codec.releaseOutputBuffer(index, false) }
                                lastActivity = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                }
                checkCancelled()
                pcm.finish()
            }
            onProgress(1f)
        } catch (error: MediaCodec.CodecException) {
            throw IOException("This device could not decode the audio track (${error.diagnosticInfo})", error)
        } finally {
            try {
                decoder?.let { codec ->
                    // A codec already in an error state may throw on stop. Still release it and
                    // retain the conversion/cancellation exception rather than masking its cause.
                    try { if (decoderStarted) codec.stop() } catch (_: Exception) { }
                    try { codec.release() } catch (_: Exception) { }
                }
            } finally { extractor.release() }
        }
    }

    private class PcmWriter(private val output: RandomAccessFile, initialRate: Int, initialChannels: Int) {
        private var sampleRate = initialRate
        private var channels = initialChannels
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var bytesWritten = 0L

        fun configure(format: MediaFormat) {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            require(rate in 1..384_000 && count in 1..8) { "Unsupported audio sample rate or channel count" }
            require(pcm in setOf(AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT)) {
                "The device decoder returned an unsupported PCM audio format"
            }
            require(bytesWritten == 0L || (sampleRate == rate && channels == count && encoding == pcm)) {
                "Audio format changes within the file; a single WAV cannot represent it"
            }
            sampleRate = rate; channels = count; encoding = pcm
        }

        fun write(buffer: ByteBuffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val sampleBytes = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> 2
            }
            require(buffer.remaining() % sampleBytes == 0) { "The decoder returned an incomplete audio sample" }
            val samples = buffer.remaining() / sampleBytes
            require(bytesWritten + samples.toLong() * 2 <= MAX_WAV_DATA_BYTES) { "WAV output exceeds the 4 GiB format limit" }
            val bytes = ByteArray(minOf(samples, 8192) * 2)
            while (buffer.hasRemaining()) {
                val count = minOf(buffer.remaining() / sampleBytes, bytes.size / 2)
                for (index in 0 until count) {
                    val value = when (encoding) {
                        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) shl 8
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val value = buffer.float
                            if (!value.isFinite()) 0 else (value.coerceIn(-1f, 1f) * 32767f).toInt()
                        }
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> { buffer.get(); val low = buffer.get().toInt() and 0xff; low or (buffer.get().toInt() shl 8) }
                        AudioFormat.ENCODING_PCM_32BIT -> buffer.int shr 16
                        else -> buffer.short.toInt()
                    }
                    bytes[index * 2] = value.toByte()
                    bytes[index * 2 + 1] = (value shr 8).toByte()
                }
                output.write(bytes, 0, count * 2)
                bytesWritten += count * 2
            }
        }

        fun finish() {
            require(bytesWritten > 0) { "No decodable audio samples were found" }
            require(bytesWritten % (channels * 2) == 0L) { "The audio stream ended with an incomplete sample frame" }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((bytesWritten + 36).toInt())
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(channels.toShort())
            header.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytesWritten.toInt())
            output.seek(0); output.write(header.array())
        }
    }
}

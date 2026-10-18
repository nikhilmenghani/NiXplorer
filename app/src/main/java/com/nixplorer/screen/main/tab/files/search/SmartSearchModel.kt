package com.nixplorer.screen.main.tab.files.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.sqrt

/** The model only creates embeddings; it has no tools, file writes or network access to user content. */
internal object SmartSearchModel {
    private const val REVISION = "751bff37182d3f1213fa05d7196b954e230abad9"
    private val files = listOf(
        Triple("onnx/model_quantized.onnx", 22972370L, "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1"),
        Triple("vocab.txt", 231508L, "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3")
    )
    var status by mutableStateOf("")
        private set
    var downloading by mutableStateOf(false)
        private set
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    fun directory(context: Context) = File(context.noBackupFilesDir, "smart-search-minilm-v1")
    fun isDownloaded(context: Context) = File(directory(context), "ready").isFile

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            downloading = true
            val directory = directory(context).apply { check(isDirectory || mkdirs()) }
            try {
                directory.listFiles().orEmpty().filter { it.name.startsWith("download-") && it.extension == "part" }.forEach { it.delete() }
                for ((remote, size, hash) in files) {
                    val target = File(directory, remote.substringAfterLast('/'))
                    if (target.isFile && target.length() == size && digest(target) == hash) continue
                    val stage = File.createTempFile("download-", ".part", directory)
                    try {
                        val connection = URL("https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/$REVISION/$remote").openConnection().apply {
                            connectTimeout = 30_000; readTimeout = 30_000
                        }
                        connection.getInputStream().use { input -> stage.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var bytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                bytes += count; require(bytes <= size) { "Model download exceeded expected size" }
                                output.write(buffer, 0, count)
                                status = "Downloading offline search: ${bytes * 100 / size}%"
                            }
                        } }
                        require(stage.length() == size && digest(stage) == hash) { "Model checksum failed. Please retry the download." }
                        java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { stage.delete() }
                }
                File(directory, "ready").writeText(REVISION)
                status = "Offline model ready"
            } catch (e: CancellationException) { status = "Download stopped. Tap Download to retry."; throw e }
            catch (e: Exception) { status = e.message ?: "Model download failed"; throw e }
            finally { downloading = false }
        }
    }

    suspend fun requiredTerms(context: Context, query: String): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = tokenizer ?: WordPieceTokenizer(File(directory(context), "vocab.txt").readLines())
            SearchRelevance.requiredTerms(query, current)
        }
    }

    suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { session?.close(); session = null; tokenizer = null }
    }

    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            session?.close(); session = null; tokenizer = null
            val directory = directory(context)
            listOf("ready", "model_quantized.onnx", "vocab.txt").forEach { name ->
                val file = File(directory, name)
                check(!file.exists() || file.delete()) { "Cannot remove offline model" }
            }
            File(context.cacheDir, "smart-search-vectors").listFiles().orEmpty().forEach { it.delete() }
            status = "Offline model removed"
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun embed(context: Context, text: String): FloatArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (session == null) {
                require(isDownloaded(context)) { "Download the 23 MB offline model to search by meaning" }
                val directory = directory(context)
                for ((remote, size, hash) in files) {
                    val file = File(directory, remote.substringAfterLast('/'))
                    require(file.length() == size && digest(file) == hash) { "Offline model is damaged. Download it again." }
                }
                tokenizer = WordPieceTokenizer(File(directory, "vocab.txt").readLines())
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    session = OrtEnvironment.getEnvironment().createSession(File(directory, "model_quantized.onnx").absolutePath, options)
                }
            }
            val env = OrtEnvironment.getEnvironment()
            val ids = tokenizer!!.encode(text)
            OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
                OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size) { 1 })).use { mask ->
                    OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size))).use { types ->
                        session!!.run(mapOf("input_ids" to input, "attention_mask" to mask, "token_type_ids" to types)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val tokens = (output[0].value as Array<Array<FloatArray>>)[0]
                            val mean = FloatArray(384)
                            tokens.forEach { token -> for (i in mean.indices) mean[i] += token[i] / tokens.size }
                            val norm = sqrt(mean.sumOf { it.toDouble() * it }).toFloat().coerceAtLeast(1e-12f)
                            for (i in mean.indices) mean[i] /= norm
                            currentCoroutineContext().ensureActive()
                            mean
                        }
                    }
                }
            }
        }
    }
    fun similarity(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()
}

package io.notepit.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.math.sqrt

data class ModelStatus(
    val installed: Boolean = false,
    val busy: Boolean = false,
    val message: String = ""
)

/**
 * English sentence-transformers MiniLM-L3, quantized by Xenova (Apache-2.0).
 * Networking exists only in the explicit, user-consented [download] operation.
 * [encode] uses CPU inference on one thread, truncating to 126 WordPieces + CLS/SEP.
 * Model files live outside Android backup; removing the model also releases its native session.
 */
class SemanticEngine(context: Context) {
    private val applicationContext = context.applicationContext
    private val root by lazy { File(applicationContext.noBackupFilesDir, "semantic-model") }
    private val installedDirectory by lazy { File(root, MODEL_REVISION) }
    private val lifecycle = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val runtimeLock = Any()
    @Volatile private var closed = false
    @Volatile private var connection: HttpsURLConnection? = null
    private var runtime: Runtime? = null
    private var verified = false
    private var integrityFailed = false
    private val mutableStatus = MutableStateFlow(ModelStatus(busy = true, message = "Checking offline model…"))
    val status: StateFlow<ModelStatus> = mutableStatus.asStateFlow()

    init {
        lifecycle.launch {
            operations.withLock {
                if (!closed) {
                    mutableStatus.value = try {
                        ModelStatus(installed = isInstalled())
                    } catch (failure: Exception) {
                        ModelStatus(message = failure.message ?: "Cannot inspect private model storage")
                    }
                }
            }
        }
    }

    /** Call only after showing model size, source, English limitations and explicit consent. */
    suspend fun download() = withContext(Dispatchers.IO) {
        operations.withLock {
            checkOpen()
            if (isInstalled()) {
                try {
                    verifyInstallation()
                    mutableStatus.value = ModelStatus(installed = true, message = "Model ready for offline search")
                    return@withLock
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // Explicit consent permits replacing damaged files, never an encode-time download.
                }
            }
            mutableStatus.value = ModelStatus(busy = true, message = "Preparing model download…")
            val staging = File(root, ".download")
            try {
                if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create private model storage")
                if (root.usableSpace < REQUIRED_FREE_BYTES) throw IOException("At least 64 MiB of free storage is required")
                if (staging.exists() && !staging.deleteRecursively()) throw IOException("Cannot clear interrupted model download")
                if (!staging.mkdirs()) throw IOException("Cannot create temporary model storage")
                var completed = 0L
                for (asset in ASSETS) {
                    currentCoroutineContext().ensureActive()
                    checkOpen()
                    downloadAsset(asset, File(staging, asset.fileName)) { bytes ->
                        val percent = ((completed + bytes) * 100 / DOWNLOAD_BYTES).toInt()
                        val message = "Downloading English model: $percent%"
                        if (mutableStatus.value.message != message) mutableStatus.value = ModelStatus(busy = true, message = message)
                    }
                    completed += asset.bytes
                }
                currentCoroutineContext().ensureActive()
                synchronized(runtimeLock) {
                    checkOpen()
                    releaseRuntime()
                    if (installedDirectory.exists() && !installedDirectory.deleteRecursively()) throw IOException("Cannot replace damaged model")
                    if (!staging.renameTo(installedDirectory)) throw IOException("Cannot atomically install model")
                    verified = true
                    integrityFailed = false
                }
                mutableStatus.value = ModelStatus(installed = true, message = "Model ready for offline search")
            } catch (cancelled: CancellationException) {
                mutableStatus.value = ModelStatus(installed = isInstalled(), message = "Model download cancelled")
                throw cancelled
            } catch (failure: Exception) {
                mutableStatus.value = ModelStatus(installed = isInstalled(), message = failure.message ?: "Model download failed")
                throw failure
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        operations.withLock {
            checkOpen()
            mutableStatus.value = ModelStatus(installed = isInstalled(), busy = true, message = "Removing model…")
            try {
                synchronized(runtimeLock) {
                    releaseRuntime()
                    verified = false
                    integrityFailed = false
                    if (root.exists() && !root.deleteRecursively()) throw IOException("Cannot remove all model files")
                }
                mutableStatus.value = ModelStatus(message = "Model removed; text search remains available")
            } catch (failure: Exception) {
                mutableStatus.value = ModelStatus(installed = isInstalled(), message = failure.message ?: "Cannot remove model")
                throw failure
            }
        }
    }

    /** Returns a 384-dimensional, mean-pooled, L2-normalized embedding; never performs network I/O. */
    suspend fun encode(text: String): FloatArray = withContext(Dispatchers.IO) {
        operations.withLock {
            checkOpen()
            check(isInstalled()) { "Download the English model explicitly before using semantic search" }
            mutableStatus.value = ModelStatus(installed = true, busy = true, message = "Running English model on device…")
            try {
                verifyInstallation()
                currentCoroutineContext().ensureActive()
                val result = synchronized(runtimeLock) {
                    checkOpen()
                    val active = runtime ?: createRuntime().also { runtime = it }
                    active.encode(text)
                }
                currentCoroutineContext().ensureActive()
                mutableStatus.value = ModelStatus(installed = true, message = "Offline English model ready")
                result
            } catch (cancelled: CancellationException) {
                mutableStatus.value = ModelStatus(installed = isInstalled(), message = "On-device inference cancelled")
                throw cancelled
            } catch (failure: Exception) {
                synchronized(runtimeLock) { releaseRuntime() }
                mutableStatus.value = ModelStatus(installed = isInstalled(), message = failure.message ?: "On-device inference failed")
                throw failure
            }
        }
    }

    /** Safe against an in-flight inference; permanently closes this engine, without deleting files. */
    fun close() {
        closed = true
        lifecycle.cancel()
        connection?.disconnect()
        synchronized(runtimeLock) { releaseRuntime() }
    }

    private fun checkOpen() = check(!closed) { "Semantic engine is closed" }

    private fun isInstalled(): Boolean = !integrityFailed && ASSETS.all { asset ->
        File(installedDirectory, asset.fileName).let { it.isFile && it.length() == asset.bytes }
    }

    private suspend fun verifyInstallation() {
        if (verified) return
        for (asset in ASSETS) {
            currentCoroutineContext().ensureActive()
            val file = File(installedDirectory, asset.fileName)
            if (file.length() != asset.bytes || sha256(file) != asset.sha256) {
                integrityFailed = true
                throw IOException("Model integrity check failed; remove and download the model again")
            }
        }
        verified = true
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private suspend fun downloadAsset(asset: Asset, destination: File, progress: (Long) -> Unit) = coroutineScope {
        var url = URL("$MODEL_BASE_URL/${asset.remoteName}")
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            checkOpen()
            val active = url.openConnection() as HttpsURLConnection
            active.instanceFollowRedirects = false
            active.connectTimeout = 20_000
            active.readTimeout = 20_000
            active.useCaches = false
            active.setRequestProperty("Accept-Encoding", "identity")
            connection = active
            // Cancelling the caller interrupts a blocked TLS read instead of waiting for the timeout.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { active.disconnect() }
            }
            try {
                val code = active.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (++redirects > 5) throw IOException("Too many HTTPS model redirects")
                    val target = active.getHeaderField("Location") ?: throw IOException("Missing model redirect")
                    val next = URL(url, target)
                    if (next.protocol != "https" || next.userInfo != null) throw IOException("Unsafe model download redirect")
                    url = next
                    continue
                }
                if (code != 200) throw IOException("Model server returned HTTP $code")
                val length = active.contentLengthLong
                if (length >= 0 && length != asset.bytes) throw IOException("Unexpected model download size")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                active.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            checkOpen()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > asset.bytes) throw IOException("Model response exceeds pinned size")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            progress(received)
                        }
                        output.fd.sync()
                    }
                }
                if (received != asset.bytes || digest.digest().hex() != asset.sha256) {
                    throw IOException("Model checksum mismatch; download discarded")
                }
                break
            } finally {
                active.disconnect()
                cancellation.cancel()
                if (connection === active) connection = null
            }
        }
    }

    private fun createRuntime(): Runtime {
        val config = JSONObject(File(installedDirectory, "config.json").readText())
        val tokenizerConfig = JSONObject(File(installedDirectory, "tokenizer_config.json").readText())
        check(config.getInt("hidden_size") == DIMENSIONS && config.getInt("vocab_size") == 30522)
        check(tokenizerConfig.getBoolean("do_lower_case") && tokenizerConfig.getBoolean("tokenize_chinese_chars"))
        val vocabulary = File(installedDirectory, "vocab.txt").readLines()
        check(vocabulary.size == 30522)
        val tokenizer = WordPieceTokenizer(vocabulary, MAX_TOKENS, MAX_TEXT_CHARS)
        val environment = OrtEnvironment.getEnvironment()
        val session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(File(installedDirectory, "model.onnx").absolutePath, options)
        }
        try {
            check(session.inputNames == setOf("input_ids", "attention_mask", "token_type_ids"))
            check("last_hidden_state" in session.outputNames)
            return Runtime(environment, session, tokenizer)
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    private fun releaseRuntime() {
        val previous = runtime
        runtime = null
        previous?.close()
    }

    /** Reusable direct input and pinned output tensors avoid per-passage tensor copies/allocations. */
    private class Runtime(
        environment: OrtEnvironment,
        private val session: OrtSession,
        private val tokenizer: WordPieceTokenizer
    ) {
        private val ids = longBuffer(MAX_TOKENS)
        private val mask = longBuffer(MAX_TOKENS)
        private val types = longBuffer(MAX_TOKENS)
        private val tokenIds = LongArray(MAX_TOKENS)
        private val output: FloatBuffer = ByteBuffer.allocateDirect(MAX_TOKENS * DIMENSIONS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val tensors = ArrayList<OnnxTensor>(4)
        private val inputs: Map<String, OnnxTensor>
        private val outputs: Map<String, OnnxTensor>

        init {
            try {
                val shape = longArrayOf(1, MAX_TOKENS.toLong())
                fun tensor(buffer: LongBuffer): OnnxTensor = OnnxTensor.createTensor(environment, buffer, shape).also { tensors.add(it) }
                inputs = mapOf("input_ids" to tensor(ids), "attention_mask" to tensor(mask), "token_type_ids" to tensor(types))
                val result = OnnxTensor.createTensor(environment, output, longArrayOf(1, MAX_TOKENS.toLong(), DIMENSIONS.toLong()))
                tensors.add(result)
                outputs = mapOf("last_hidden_state" to result)
            } catch (failure: Throwable) {
                tensors.forEach { it.close() }
                throw failure
            }
        }

        fun encode(text: String): FloatArray {
            val length = tokenizer.encodeInto(text, tokenIds)
            for (index in 0 until MAX_TOKENS) {
                ids.put(index, tokenIds[index])
                mask.put(index, if (index < length) 1L else 0L)
            }
            session.run(inputs, outputs).use { /* Pinned outputs remain owned by this runtime. */ }
            val embedding = FloatArray(DIMENSIONS)
            for (token in 0 until length) {
                val offset = token * DIMENSIONS
                for (dimension in 0 until DIMENSIONS) embedding[dimension] += output.get(offset + dimension)
            }
            var squaredNorm = 0.0
            for (dimension in embedding.indices) {
                embedding[dimension] /= length.toFloat()
                squaredNorm += embedding[dimension].toDouble() * embedding[dimension]
            }
            check(squaredNorm.isFinite() && squaredNorm > 1e-12) { "Model produced an invalid embedding" }
            val norm = sqrt(squaredNorm).toFloat()
            for (dimension in embedding.indices) embedding[dimension] /= norm
            return embedding
        }

        fun close() {
            try { tensors.forEach { it.close() } } finally { session.close() }
        }

        private fun longBuffer(size: Int): LongBuffer = ByteBuffer.allocateDirect(size * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
    }

    private data class Asset(val remoteName: String, val fileName: String, val bytes: Long, val sha256: String)

    companion object {
        const val MODEL_NAME = "MiniLM-L3-v2 (English, INT8)"
        const val MODEL_REVISION = "4b544e74dfc3256b2b56849ea5d7064fee1ac846"
        const val MODEL_BASE_URL = "https://huggingface.co/Xenova/paraphrase-MiniLM-L3-v2/resolve/$MODEL_REVISION"
        const val DOWNLOAD_BYTES = 17_684_637L
        const val STORAGE_BYTES = DOWNLOAD_BYTES
        const val REQUIRED_FREE_BYTES = 64L * 1024 * 1024
        const val DIMENSIONS = 384
        const val MAX_TOKENS = 128
        const val MAX_TEXT_CHARS = 8192

        // Exact bytes hashed from the immutable revision; ONNX hash also matches HF's LFS SHA-256.
        private val ASSETS = listOf(
            Asset("onnx/model_quantized.onnx", "model.onnx", 17_452_106L, "b190f50dd46296b9895ae8f274c3455762d08610f8788f4a9bd15019f4f7160c"),
            Asset("vocab.txt", "vocab.txt", 231_508L, "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
            Asset("tokenizer_config.json", "tokenizer_config.json", 366L, "9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3"),
            Asset("config.json", "config.json", 657L, "c8bcaca23b245d64047ee04fa2edcc55867064b67cbbe2772f660cdbdfb1978c")
        )

        private fun ByteArray.hex(): String {
            val digits = "0123456789abcdef"
            return buildString(size * 2) {
                for (byte in this@hex) {
                    val value = byte.toInt() and 255
                    append(digits[value ushr 4])
                    append(digits[value and 15])
                }
            }
        }
    }
}

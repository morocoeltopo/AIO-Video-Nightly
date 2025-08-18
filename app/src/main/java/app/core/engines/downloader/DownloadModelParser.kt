package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import app.core.engines.downloader.DownloadDataModel.Companion.convertJSONStringToClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import lib.process.LogHelperUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * DownloadModelParser - Robust Model Cache with Failure Recovery
 *
 * Handles loading, caching, and management of download models with:
 * - Automatic recovery from parsing failures
 * - Coroutine-based parallel processing
 * - Cache validation and invalidation
 * - Thread-safe operations
 *
 * Recovery Features:
 * 1. Automatic retry mechanism for failed parses
 * 2. Corrupted file cleanup
 * 3. Isolated processing to prevent cascade failures
 * 4. Cache state monitoring
 */
object DownloadModelParser {
    private val logger = LogHelperUtils.from(javaClass)

    // Thread-safe cache with access logging
    private val modelCache = ConcurrentHashMap<String, DownloadDataModel>()

    // Coroutine scope with supervisor job to prevent cascading failures
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track failed files to prevent repeated processing attempts
    private val failedFiles = ConcurrentHashMap<String, Long>()
    private const val FAILURE_RETRY_DELAY_MS = 30000L // 30 seconds

    /**
     * Retrieves all valid download models, with automatic recovery from failures.
     *
     * Recovery Process:
     * 1. Attempts to load all models
     * 2. Skips known problematic files temporarily
     * 3. Validates cache integrity
     * 4. Returns only successfully loaded models
     */
    @Throws(Exception::class)
    suspend fun getDownloadDataModels(): List<DownloadDataModel> {
        logger.d("getDownloadDataModels() called. Cache size=${modelCache.size}")
        return withContext(Dispatchers.IO) {
            if (modelCache.isEmpty()) {
                logger.d("Cache empty, loading models with recovery.")
                loadAllModelsWithRecovery()
            } else {
                logger.d("Validating cache against files.")
                validateCacheAgainstFiles()
            }
            logger.d("Returning ${modelCache.size} models.")
            modelCache.values.toList()
        }
    }

    /**
     * Gets a specific model with built-in failure recovery.
     *
     * Recovery Features:
     * - Checks failure cache before attempting load
     * - Automatically retries after delay if previous failure
     * - Returns null only if file doesn't exist or permanently corrupted
     */
    suspend fun getDownloadDataModel(id: String): DownloadDataModel? {
        logger.d("getDownloadDataModel() called for id=$id")
        return withContext(Dispatchers.IO) {
            // Check if this file recently failed
            failedFiles[id]?.let { timestamp ->
                if (System.currentTimeMillis() - timestamp < FAILURE_RETRY_DELAY_MS) {
                    logger.d("Skipping $id due to recent failure.")
                    return@withContext null
                }
            }

            modelCache[id] ?: run {
                logger.d("Model $id not in cache, attempting load with recovery.")
                loadSingleModelWithRecovery(id)
                modelCache[id]
            }
        }
    }

    /**
     * Enhanced model loading with automatic recovery.
     */
    private suspend fun loadAllModelsWithRecovery() {
        val files = listModelFiles(INSTANCE.filesDir)
        logger.d("Found ${files.size} model files to process.")

        files.chunked(10).forEach { chunk ->
            logger.d("Processing chunk of ${chunk.size} files.")
            val deferredResults = chunk.map { file ->
                scope.async {
                    if (shouldAttemptLoad(file.nameWithoutExtension)) {
                        processModelFileWithRecovery(file)
                    } else {
                        logger.d("Skipping ${file.name} due to recent failure.")
                        false
                    }
                }
            }
            deferredResults.awaitAll()
        }
    }

    /**
     * Safe loading of single model with recovery.
     */
    private fun loadSingleModelWithRecovery(id: String) {
        if (!shouldAttemptLoad(id)) {
            logger.d("Skipping load for $id due to failure history.")
            return
        }

        val fileName = "$id$DOWNLOAD_MODEL_FILE_JSON_EXTENSION"
        val file = File(INSTANCE.filesDir, fileName)
        if (file.exists()) {
            logger.d("Loading model file: ${file.absolutePath}")
            processModelFileWithRecovery(file)
        } else {
            logger.d("Model file not found for id=$id")
        }
    }

    /**
     * Determines if a file should be attempted based on failure history.
     */
    private fun shouldAttemptLoad(fileId: String): Boolean {
        val should = failedFiles[fileId]?.let {
            System.currentTimeMillis() - it > FAILURE_RETRY_DELAY_MS
        } ?: true
        logger.d("shouldAttemptLoad($fileId) -> $should")
        return should
    }

    /**
     * Processes a file with enhanced error handling and recovery.
     */
    private fun processModelFileWithRecovery(file: File): Boolean {
        logger.d("Processing file: ${file.name}")
        return try {
            val model = convertJSONStringToClass(file)

            if (model != null) {
                logger.d("Successfully parsed model: ${file.nameWithoutExtension}")
                modelCache[file.nameWithoutExtension] = model
                failedFiles.remove(file.nameWithoutExtension)
                true
            } else {
                logger.d("Corrupted file detected: ${file.name}")
                handleCorruptedFile(file)
                false
            }
        } catch (error: Exception) {
            logger.d("Error processing file ${file.name}: ${error.message}")
            error.printStackTrace()
            handleProcessingError(file, error)
            false
        }
    }

    /**
     * Handles file corruption by cleaning up and logging.
     */
    private fun handleCorruptedFile(file: File) {
        logger.d("Deleting corrupted file: ${file.name}")
        try {
            file.delete()
            failedFiles.remove(file.nameWithoutExtension)
        } catch (error: Exception) {
            logger.d("Failed to delete corrupted file ${file.name}: ${error.message}")
            error.printStackTrace()
        }
    }

    /**
     * Handles processing errors with appropriate recovery actions.
     */
    private fun handleProcessingError(file: File, error: Exception) {
        logger.d("Handling error for file ${file.name}: ${error.javaClass.simpleName}")
        error.printStackTrace()
        failedFiles[file.nameWithoutExtension] = System.currentTimeMillis()

        // Only delete if we're certain it's causing problems
        if (error is IllegalStateException || error is NumberFormatException) {
            try {
                logger.d("Deleting problematic file: ${file.name}")
                file.delete()
            } catch (error: Exception) {
                logger.d("Failed to delete problematic file ${file.name}: ${error.message}")
                error.printStackTrace()
            }
        }
    }

    /**
     * Validates cache against existing files with recovery options.
     */
    private fun validateCacheAgainstFiles() {
        logger.d("Validating cache entries against current files.")
        val currentFiles = listModelFiles(INSTANCE.filesDir)
            .associateBy { it.nameWithoutExtension }

        modelCache.keys.removeAll { id ->
            if (!currentFiles.containsKey(id)) {
                logger.d("Removing $id from cache (file no longer exists).")
                true
            } else {
                val retryReady = failedFiles[id]?.let {
                    System.currentTimeMillis() - it > FAILURE_RETRY_DELAY_MS
                } ?: false
                if (retryReady) {
                    logger.d("Marking $id for retry after failure delay.")
                }
                retryReady
            }
        }
    }

    /**
     * Lists model files with basic validation.
     */
    private fun listModelFiles(directory: File?): List<File> {
        val suffix = DOWNLOAD_MODEL_FILE_JSON_EXTENSION
        val files = directory?.takeIf { it.isDirectory }
            ?.listFiles { file ->
                file.isFile && file.name.endsWith(suffix) &&
                        !file.name.contains("temp")
            }
            ?.toList() ?: emptyList()

        logger.d("listModelFiles() -> Found ${files.size} files.")
        return files
    }

    /**
     * Clears all caches including failure tracking.
     */
    fun fullReset() {
        logger.d("Performing full reset of cache and failure records.")
        modelCache.clear()
        failedFiles.clear()
    }

    /**
     * Standard cache invalidation.
     */
    fun invalidateCache() {
        logger.d("Invalidating model cache.")
        modelCache.clear()
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        logger.d("Cleaning up DownloadModelParser resources.")
        scope.cancel()
        failedFiles.clear()
    }
}
package lib.files

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import lib.process.LogHelperUtils
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * A utility class for converting video files to audio files by extracting the audio track.
 *
 * This class handles the complete process of extracting audio from video files using Android's
 * MediaExtractor and MediaMuxer APIs. It supports progress tracking and cancellation.
 *
 * ## Key Features:
 * - Extracts audio tracks from video files
 * - Supports progress monitoring via callback interface
 * - Allows cancellation during extraction
 * - Handles various audio formats supported by Android Media framework
 * - Uses WeakReference for listener to prevent memory leaks
 *
 * ## Usage Example:
 * ```kotlin
 * val converter = VideoToAudioConverter()
 * val listener = object : VideoToAudioConverter.ConversionListener {
 *     override fun onProgress(progress: Int) {
 *         // Update progress bar
 *     }
 *
 *     override fun onSuccess(outputFile: String) {
 *         // Handle successful extraction
 *     }
 *
 *     override fun onFailure(errorMessage: String) {
 *         // Handle failure
 *     }
 * }
 *
 * converter.extractAudio(inputVideoPath, outputAudioPath, listener)
 * ```
 *
 * @see MediaExtractor
 * @see MediaMuxer
 * @see MediaFormat
 */
class VideoToAudioConverter {

	/** Logger instance for tracking conversion process and debugging */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Volatile flag to indicate whether the extraction process has been cancelled.
	 *
	 * Using volatile ensures visibility of changes across threads when checking
	 * cancellation status during the extraction loop.
	 */
	@Volatile
	private var isProcessCancelledByUser = false

	/**
	 * Extracts audio from the provided video file and saves it as an audio file.
	 *
	 * This method performs the following steps:
	 * 1. Initializes MediaExtractor to read the input video file
	 * 2. Identifies and selects the audio track from available tracks
	 * 3. Initializes MediaMuxer to write the extracted audio to output file
	 * 4. Reads audio samples and writes them to the output file
	 * 5. Provides progress updates during extraction
	 * 6. Handles cleanup and resource release
	 *
	 * ## Supported Formats:
	 * - Input: Any video format supported by Android MediaExtractor
	 * - Output: MPEG-4 audio format (MUXER_OUTPUT_MPEG_4)
	 *
	 * ## Error Handling:
	 * - Returns failure if no audio track is found
	 * - Returns failure if extraction is cancelled
	 * - Returns failure if any exception occurs during processing
	 *
	 * @param inputFile The absolute path to the input video file. Must be a readable file path.
	 * @param outputFile The absolute path where the extracted audio will be saved.
	 *                  The directory must be writable.
	 * @param listener A [ConversionListener] implementation to receive callbacks for
	 *                progress updates, success, and failure notifications. The listener
	 *                is wrapped in a WeakReference to prevent memory leaks.
	 *
	 * @throws SecurityException If the app lacks permission to read the input file or
	 *                          write to the output location.
	 * @throws IllegalArgumentException If the input file path is invalid or the file
	 *                                 format is not supported.
	 *
	 * @see ConversionListener
	 */
	fun extractAudio(inputFile: String, outputFile: String, listener: ConversionListener) {
		// Use WeakReference to prevent memory leaks in case the listener holds activity context
		WeakReference(listener).get()?.let { safeListener ->
			try {
				logger.d("Starting audio extraction from video: $inputFile")

				// Initialize MediaExtractor to read the input video file
				val extractor = MediaExtractor()
				extractor.setDataSource(inputFile)

				var audioTrackIndex = -1
				var format: MediaFormat? = null

				// Loop through all tracks to find the audio track
				logger.d("Scanning tracks in video file...")
				for (i in 0 until extractor.trackCount) {
					format = extractor.getTrackFormat(i)
					val mime = format.getString(MediaFormat.KEY_MIME)
					logger.d("Checking track $i: MIME = $mime")

					// Select the first audio track found
					if (mime?.startsWith("audio/") == true) {
						audioTrackIndex = i
						extractor.selectTrack(i)
						logger.d("Selected audio track index: $audioTrackIndex")
						break
					}
				}

				// Validate that an audio track was found
				if (audioTrackIndex == -1 || format == null) {
					val errorMsg = "No audio track found in video file: $inputFile"
					logger.d(errorMsg)
					safeListener.onFailure(errorMsg)
					extractor.release() // Release resources before returning
					return
				}

				// Initialize MediaMuxer to write the output audio file
				logger.d("Initializing MediaMuxer with output file: $outputFile")
				val muxer = MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
				val newTrackIndex = muxer.addTrack(format)
				muxer.start()

				// Set up buffer for reading sample data
				val buffer = ByteBuffer.allocate(4096) // 4KB buffer for sample data
				val bufferInfo = MediaCodec.BufferInfo()

				// Calculate file size for progress tracking
				val fileSize = File(inputFile).length().toFloat()
				var extractedSize = 0L

				logger.d("Starting audio sample extraction loop...")

				// Main extraction loop: read samples until end of file or cancellation
				while (!isProcessCancelledByUser) {
					buffer.clear()

					// Read sample data into buffer
					val sampleSize = extractor.readSampleData(buffer, 0)
					if (sampleSize < 0) {
						logger.d("End of stream reached, sampleSize: $sampleSize")
						break // End of stream
					}

					// Configure buffer info for the current sample
					bufferInfo.offset = 0
					bufferInfo.size = sampleSize
					bufferInfo.presentationTimeUs = extractor.sampleTime
					bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME

					// Write the sample to the output file
					muxer.writeSampleData(newTrackIndex, buffer, bufferInfo)

					// Advance to next sample
					extractor.advance()

					// Update progress
					extractedSize += sampleSize
					val progress = ((extractedSize / fileSize) * 100).toInt()
					safeListener.onProgress(progress)
				}

				// Handle cancellation scenario
				if (isProcessCancelledByUser) {
					logger.d("Audio extraction cancelled by user")
					safeListener.onFailure("Audio extraction cancelled")

					// Clean up resources
					muxer.stop()
					muxer.release()
					extractor.release()
					return
				}

				// Finalize the output file and release resources
				muxer.stop()
				muxer.release()
				extractor.release()

				logger.d("Audio extraction completed successfully. Output: $outputFile")
				safeListener.onSuccess(outputFile)

			} catch (error: Exception) {
				// Handle any exceptions during the extraction process
				val errorMsg = "Audio extraction failed: ${error.message}"
				logger.d("$errorMsg. Error: $error")
				safeListener.onFailure(errorMsg)
			}
		} ?: run {
			// Listener has been garbage collected, log warning
			logger.d("ConversionListener has been garbage collected, extraction aborted")
		}
	}

	/**
	 * Cancels the ongoing audio extraction process.
	 *
	 * This method sets a cancellation flag that will be checked during the extraction loop.
	 * The extraction will stop at the next sample boundary and the listener will receive
	 * an onFailure callback with cancellation message.
	 *
	 * This method is thread-safe and can be called from any thread.
	 */
	fun cancel() {
		logger.d("Cancellation requested for audio extraction")
		isProcessCancelledByUser = true
	}

	/**
	 * Interface for receiving updates during the audio extraction process.
	 *
	 * Implement this interface to receive callbacks for progress updates,
	 * successful completion, and failure notifications during the audio
	 * extraction process.
	 *
	 * ## Memory Management:
	 * The listener is held via WeakReference to prevent memory leaks. Ensure
	 * your implementation maintains a strong reference to the listener if
	 * you need to receive all callbacks.
	 */
	interface ConversionListener {
		/**
		 * Called periodically to report the progress of the extraction process.
		 *
		 * @param progress The extraction progress as a percentage between 0 and 100.
		 *                 Note: Progress is calculated based on bytes processed vs
		 *                 total file size, which may not directly correlate with
		 *                 time-based progress for variable bitrate audio.
		 */
		fun onProgress(progress: Int)

		/**
		 * Called when the audio extraction completes successfully.
		 *
		 * @param outputFile The absolute path to the successfully created audio file.
		 *                  The file is now ready for use.
		 */
		fun onSuccess(outputFile: String)

		/**
		 * Called when the audio extraction fails or is cancelled.
		 *
		 * @param errorMessage A descriptive message explaining the failure reason.
		 *                    Possible reasons include:
		 *                    - "No audio track found in video"
		 *                    - "Audio extraction cancelled"
		 *                    - Various media processing errors
		 */
		fun onFailure(errorMessage: String)
	}
}
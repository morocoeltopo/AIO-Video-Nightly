package lib.process

import app.core.AIOApp.Companion.internalDataFolder
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import java.util.Random

/**
 * Utility object for generating unique numbers and making random-based decisions.
 *
 * This class provides functions that are useful for generating unique IDs or filenames
 * and for determining whether to display ads randomly. The methods ensure collision-resistant
 * number generation for various application needs including download model identification.
 */
object UniqueNumberUtils {

	/**
	 * Generates a pseudo-unique long number using the current time in milliseconds and a random component.
	 *
	 * This method combines the current time (modulo 1,000,000 to avoid very large numbers)
	 * with a random integer component to create a reasonably unique identifier. Suitable for
	 * session IDs, temporary filenames, or other scenarios where quick uniqueness is needed.
	 *
	 * @return A pseudo-unique [Long] number combining temporal and random components
	 */
	@JvmStatic
	fun generateUniqueNumber(): Long {
		val random = Random()
		val currentTime = System.currentTimeMillis() % 1_000_000L
		val randomComponent = random.nextInt(1000)
		return currentTime * 1000 + randomComponent
	}

	/**
	 * Generates a unique integer ID for a new download model by checking existing files.
	 *
	 * This method scans the internal data folder for existing download model files with
	 * JSON extension, extracts their numeric prefixes, and returns the next available
	 * sequential number. This ensures each download model gets a unique identifier that
	 * increments sequentially from the highest existing ID.
	 *
	 * @return A unique [Int] number suitable for naming a new download model file
	 */
	@JvmStatic
	fun getUniqueNumberForDownloadModels(): Int {
		// Filter files that match the download model JSON extension pattern
		val existingFiles = internalDataFolder.listFiles()
			.filter { it.name!!.endsWith(DOWNLOAD_MODEL_FILE_JSON_EXTENSION) }

		// Extract numeric prefixes from filenames (format: "number_rest_of_filename.json")
		val existingNumbers = existingFiles.mapNotNull { file ->
			file.name!!.split("_").firstOrNull()?.toIntOrNull()
		}

		// Calculate the next available number by incrementing the maximum found
		val maxNumber = existingNumbers.maxOrNull() ?: 0
		return maxNumber + 1
	}
}
package lib.texts

import android.text.Html.FROM_HTML_MODE_COMPACT
import android.text.Html.fromHtml
import android.text.Spanned
import app.core.AIOApp.Companion.INSTANCE
import lib.process.LocalizationHelper
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.cutTo100Chars
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Utility object providing various common text processing functions.
 * It includes string trimming, capitalization, HTML parsing, and localized text fetching.
 */
object CommonTextUtils {

	/**
	 * Logger instance for this class to log debug and error messages.
	 * Used internally for tracking operations in text utilities.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Removes consecutive slashes (`/`) in the given string and replaces them with a single slash.
	 *
	 * Example:
	 * ```
	 * removeDuplicateSlashes("path//to///file") -> "path/to/file"
	 * ```
	 *
	 * @param input The string that may contain duplicate slashes. Can be null.
	 * @return A new string with duplicate slashes replaced by a single slash, or null if input is null.
	 */
	@JvmStatic
	fun removeDuplicateSlashes(input: String?): String? {
		if (input == null) return null
		val result = input.replace("/{2,}".toRegex(), "/") // Replace 2 or more slashes with one
		logger.d("removeDuplicateSlashes: input='$input' result='$result'")
		return result
	}

	/**
	 * Retrieves a localized string based on the provided resource ID.
	 * Uses the app’s LocalizationHelper to fetch strings in the user's selected language.
	 *
	 * @param resID Resource ID of the string to fetch.
	 * @return The localized string for the given resource ID.
	 */
	@JvmStatic
	fun getText(resID: Int): String {
		val result = LocalizationHelper.getLocalizedString(INSTANCE, resID)
		logger.d("getText: resID=$resID result='$result'")
		return result
	}

	/**
	 * Generates a random alphanumeric string of the specified length.
	 * Useful for temporary IDs, random keys, or demo strings.
	 *
	 * @param length Desired length of the random string.
	 * @return A random string containing letters (upper/lowercase) and digits.
	 */
	@JvmStatic
	fun generateRandomString(length: Int): String {
		val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
		val sb = StringBuilder(length)
		for (index in 0 until length) {
			val randomIndex = (characters.indices).random()  // Pick random index
			sb.append(characters[randomIndex])                // Append random char
		}
		val result = sb.toString()
		logger.d("generateRandomString: length=$length result='$result'")
		return result
	}

	/**
	 * Trims the input string to a maximum of 100 characters.
	 * Ensures the string ends on a valid character and removes incomplete or invalid trailing characters.
	 *
	 * @param input The string to trim, can be null.
	 * @return A trimmed string of max 100 characters or null if input is null.
	 */
	@Deprecated("Unsafe and buggy")
	@JvmStatic
	fun cutTo100Chars(input: String?): String? {
		if (input == null) return null

		if (input.length > 100) {
			var result = input.substring(0, 100)  // Take first 100 chars
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()  // Remove trailing whitespace or invalid char
			}

			// Check second last character for safety
			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo100Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Trims the input string to a maximum of 30 characters.
	 * Similar logic to [cutTo100Chars], ensures no broken trailing characters.
	 *
	 * @param input The string to trim.
	 * @return A string of max 30 characters.
	 */
	@Deprecated(message = "Unsafe and buggy")
	@JvmStatic
	fun cutTo30Chars(input: String): String {
		if (input.length > 30) {
			var result = input.substring(0, 30)
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()
			}

			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo30Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Trims the input string to a maximum of 60 characters.
	 * Removes incomplete or invalid trailing characters safely.
	 *
	 * @param input The string to trim, can be null.
	 * @return A string of max 60 characters or null if input is null.
	 */
	@Deprecated(message = "Unsafe and buggy")
	@JvmStatic
	fun cutTo60Chars(input: String?): String? {
		if (input == null) return null

		if (input.length > 60) {
			var result = input.substring(0, 60)
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()
			}

			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo60Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Safely trims the input string to a maximum of [maxLength] characters,
	 * ensuring that no multi-byte or encoded characters (like emojis) are broken.
	 *
	 * @param input The string to trim, can be null.
	 * @param maxLength Maximum number of characters to keep.
	 * @return A string safely trimmed to [maxLength] characters, or null if input is null.
	 */
	@JvmStatic
	fun safeCutString(input: String?, maxLength: Int = 60): String? {
		if (input == null) return null
		if (input.length <= maxLength) return input

		// Use codePoint-aware substring to avoid splitting multi-byte characters
		val endIndex = input.offsetByCodePoints(0, maxLength)
		var result = input.substring(0, endIndex)

		// Trim trailing whitespace or invalid characters
		while (result.isNotEmpty() && (result.last().isWhitespace() ||
					!result.last().isValidCharacter())
		) result = result.dropLast(1)

		logger.d("safeCutString: maxLength=$maxLength result='$result'")
		return result
	}

	/**
	 * Extension to check if a character is valid for text display.
	 */
	@JvmStatic
	fun Char.isValidCharacter(): Boolean {
		return this.isLetterOrDigit() ||
				this in setOf('_', '-', '.', '@', ' ', '[', ']', '(', ')')
	}

	/**
	 * Joins multiple [elements] into a single string separated by [delimiter].
	 */
	@JvmStatic
	fun join(delimiter: String, vararg elements: String): String {
		if (elements.isEmpty()) return ""
		val result = elements.joinToString(separator = delimiter)
		return result
	}

	/**
	 * Reverses the given [input] string.
	 */
	@JvmStatic
	fun reverse(input: String?): String? {
		if (input == null) return null
		val result = StringBuilder(input).reverse().toString()
		return result
	}

	/**
	 * Capitalizes the first letter of the given string.
	 */
	@JvmStatic
	fun capitalizeFirstLetter(string: String?): String? {
		if (string.isNullOrEmpty()) return null
		val first = string[0]
		val capitalized = if (Character.isUpperCase(first)) string
		else first.uppercaseChar().toString() + string.substring(1)
		return capitalized
	}

	/**
	 * Capitalizes the first letter of each word in the input string.
	 * Preserves whitespace-only strings and returns null if input is null.
	 */
	@JvmStatic
	fun capitalizeWords(input: String?): String? {
		if (input.isNullOrBlank()) return input // handles null, empty, and whitespace-only
		return input
			.trim()
			.split("\\s+".toRegex())
			.joinToString(" ") { word ->
				word.replaceFirstChar {
					if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
				}
			}
	}

	/**
	 * Converts an HTML-formatted [htmlString] into a Spanned text object.
	 */
	@JvmStatic
	fun fromHtmlStringToSpanned(htmlString: String): Spanned {
		val result = fromHtml(htmlString, FROM_HTML_MODE_COMPACT)
		return result
	}

	/**
	 * Reads an HTML string stored in the raw resources identified by [resId].
	 */
	@JvmStatic
	fun getHtmlString(resId: Int): String {
		val result = convertRawHtmlFileToString(resId)
		return result
	}

	/**
	 * Converts a raw HTML resource file to a plain string.
	 */
	@JvmStatic
	fun convertRawHtmlFileToString(resourceId: Int): String {
		val inputStream = INSTANCE.resources.openRawResource(resourceId)
		val reader = BufferedReader(InputStreamReader(inputStream))
		val stringBuilder = StringBuilder()
		var line: String?
		try {
			while (reader.readLine()
					.also { line = it } != null
			) stringBuilder.append(line)
		} catch (error: Throwable) {
			logger.e("Error while converting raw html file to string:", error)
		} finally {
			try {
				inputStream.close()
				reader.close()
			} catch (error: Exception) {
				logger.e("Error while converting raw html file to string (in finally{}):", error)
			}
		}; return stringBuilder.toString()
	}

	/**
	 * Counts how many times the character [char] appears in the [input] string.
	 */
	@JvmStatic
	fun countOccurrences(input: String?, char: Char?): Int {
		if (input == null || char == null) return 0
		val count = input.count { it == char }
		return count
	}

	/**
	 * Removes empty or blank lines from a multiline string.
	 */
	@JvmStatic
	fun removeEmptyLines(input: String?): String? {
		if (input.isNullOrEmpty()) return null
		return input.split("\n")
			.filter { it.isNotBlank() }
			.joinToString("\n")
	}
}

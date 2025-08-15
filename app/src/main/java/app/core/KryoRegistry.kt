package app.core

import app.core.engines.browser.bookmarks.AIOBookmarks
import app.core.engines.browser.bookmarks.BookmarkModel
import app.core.engines.browser.history.AIOHistory
import app.core.engines.browser.history.HistoryModel
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.settings.AIOSettings
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoInfo
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.serializers.DefaultSerializers.StringSerializer
import java.util.Date

/**
 * Centralized registry for all classes that need to be serialized/deserialized using Kryo.
 *
 * Kryo requires registering all classes that will be serialized if `isRegistrationRequired = true`.
 * This helps ensure faster serialization, reduced output size, and avoids `ClassNotFoundException`
 * or mismatched class ID errors.
 *
 * IMPORTANT:
 * - Each class is assigned a unique registration ID (second parameter in `register()`).
 * - IDs must remain consistent across app versions to maintain backward compatibility for saved data.
 * - Always register both interface types (e.g., List, Map) and their concrete implementations (e.g., ArrayList, HashMap).
 * - Also register Kotlin immutable/singleton collection classes (emptyList, emptyMap, emptySet).
 */
object KryoRegistry {

	/**
	 * Registers all necessary classes into the given Kryo instance.
	 *
	 * @param kryo the Kryo instance to register classes with
	 */
	@Synchronized
	fun registerClasses(kryo: Kryo) {
		kryo.isRegistrationRequired = true

		// --- Core Java types ---
		kryo.register(String::class.java, StringSerializer(), 10) // String with custom serializer
		kryo.register(List::class.java, 11)                        // Interface
		kryo.register(ArrayList::class.java, 12)                   // Mutable list
		kryo.register(Map::class.java, 13)                         // Interface
		kryo.register(HashMap::class.java, 14)                     // Mutable map
		kryo.register(HashSet::class.java, 15)                     // Mutable set
		kryo.register(Date::class.java, 16)                        // java.util.Date
		kryo.register(LongArray::class.java, 17)
		kryo.register(IntArray::class.java, 18)
		kryo.register(DoubleArray::class.java, 19)
		kryo.register(FloatArray::class.java, 20)
		kryo.register(BooleanArray::class.java, 21)

		// --- Kotlin singleton / immutable collections ---
		kryo.register(emptyList<Any>().javaClass, 22)              // Immutable empty list
		kryo.register(emptyMap<Any, Any>().javaClass, 23)          // Immutable empty map (generic)
		kryo.register(emptyMap<String, String>().javaClass, 52)    // Immutable empty map (String-to-String)
		kryo.register(emptyList<VideoFormat>().javaClass, 53)
		kryo.register(emptyList<VideoInfo>().javaClass, 53)

		kryo.register(emptySet<Any>().javaClass, 24)               // Immutable empty set
		kryo.register(emptyList<BookmarkModel>().javaClass, 25)    // Immutable empty list of bookmarks
		kryo.register(emptyList<HistoryModel>().javaClass, 26)     // Immutable empty list of history items

		// --- Common Android / platform types ---
		kryo.register(android.net.Uri::class.java, 27)
		kryo.register(android.os.Bundle::class.java, 28)
		kryo.register(android.util.SparseArray::class.java, 29)

		// --- Download-related ---
		kryo.register(DownloadDataModel::class.java, 30)
		kryo.register(VideoInfo::class.java, 31)
		kryo.register(VideoFormat::class.java, 32)
		kryo.register(AIOSettings::class.java, 33)

		// --- Bookmarks ---
		kryo.register(BookmarkModel::class.java, 40)
		kryo.register(AIOBookmarks::class.java, 41)

		// --- History ---
		kryo.register(HistoryModel::class.java, 50)
		kryo.register(AIOHistory::class.java, 51)
	}
}

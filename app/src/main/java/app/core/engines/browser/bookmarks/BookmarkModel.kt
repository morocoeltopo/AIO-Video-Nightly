package app.core.engines.browser.bookmarks

import com.google.gson.annotations.SerializedName
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable
import java.util.Date

/**
 * Represents a bookmark entry in the browser engine.
 *
 * This model holds all relevant metadata associated with a bookmarked webpage,
 * including URL, title, tags, ratings, notes, and sharing information.
 * It also provides serialization support for persistence and network communication.
 *
 * @constructor Creates a new instance of BookmarkModel with default values.
 */
@Entity
class BookmarkModel : Serializable {

	/**
	 * Unique identifier for the bookmarks record in ObjectBox database.
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/** The URL of the bookmarked web page.
	 * This is the primary reference used to navigate to the saved content.
	 */
	@SerializedName("bookmarkUrl")
	var bookmarkUrl: String = ""

	/** A user-defined name or title for the bookmark.
	 * This provides a human-readable identifier for easy recognition.
	 */
	@SerializedName("bookmarkName")
	var bookmarkName: String = ""

	/** The date and time when the bookmark was created.
	 * Useful for sorting, filtering, or tracking when a bookmark was first added.
	 */
	@SerializedName("bookmarkCreationDate")
	var bookmarkCreationDate: Date = Date()

	/** The date and time when the bookmark was last modified.
	 * Helps users know the most recent update or edit.
	 */
	@SerializedName("bookmarkModifiedDate")
	var bookmarkModifiedDate: Date = Date()

	/** File path to a local thumbnail image representing the bookmark.
	 * Enhances UI by showing a visual preview of the bookmarked page.
	 */
	@SerializedName("bookmarkThumbFilePath")
	var bookmarkThumbFilePath: String = ""

	/** A brief description or summary of the bookmark.
	 * Users can add notes or explanations about why the page was bookmarked.
	 */
	@SerializedName("bookmarkDescription")
	var bookmarkDescription: String = ""

	/** A list of user-defined tags used to categorize or group bookmarks.
	 * Tags enable efficient searching and filtering across bookmarks.
	 */
	@SerializedName("bookmarkTags")
	var bookmarkTags: ArrayList<String> = ArrayList()

	/** Indicates whether the bookmark is marked as a favorite.
	 * This flag helps prioritize or highlight frequently used bookmarks.
	 */
	@SerializedName("bookmarkFavorite")
	var bookmarkFavorite: Boolean = false

	/** Name of the folder or category this bookmark belongs to.
	 * Helps in organizing bookmarks into logical groups.
	 */
	@SerializedName("bookmarkFolder")
	var bookmarkFolder: String = ""

	/** Tracks how many times this bookmark has been accessed.
	 * Useful for analytics or sorting by usage frequency.
	 */
	@SerializedName("bookmarkAccessCount")
	var bookmarkAccessCount: Int = 0

	/** Stores the last time the bookmark was accessed, in string format.
	 * Allows for quick reference and activity tracking.
	 */
	@SerializedName("bookmarkLastAccessed")
	var bookmarkLastAccessed: String = ""

	/** A user-assigned rating for the bookmark.
	 * Typically ranges from 0.0 to 5.0, allowing users to rank bookmarks based on preference.
	 */
	@SerializedName("bookmarkRating")
	var bookmarkRating: Float = 0.0f

	/** Priority level for the bookmark.
	 * Used for sorting, filtering, or determining which bookmarks should be highlighted first.
	 */
	@SerializedName("bookmarkPriority")
	var bookmarkPriority: Int = 0

	/** Indicates whether the bookmark is archived.
	 * Archived bookmarks are not actively used but are retained for historical reference.
	 */
	@SerializedName("bookmarkArchived")
	var bookmarkArchived: Boolean = false

	/** URL or local path to an icon representing the bookmark.
	 * Provides a visual cue in the user interface.
	 */
	@SerializedName("bookmarkIcon")
	var bookmarkIcon: String = ""

	/** Additional notes related to the bookmark.
	 * Users can add detailed comments or instructions for reference.
	 */
	@SerializedName("bookmarkNotes")
	var bookmarkNotes: String = ""

	/** The owner or creator of the bookmark.
	 * In shared environments, this helps identify the user responsible for the bookmark.
	 */
	@SerializedName("bookmarkOwner")
	var bookmarkOwner: String = ""

	/** List of users or email addresses with whom the bookmark is shared.
	 * Enables collaboration by granting access to specific individuals.
	 */
	@SerializedName("bookmarkSharedWith")
	var bookmarkSharedWith: ArrayList<String> = ArrayList()

	/**
	 * Resets all fields in the bookmark to their default initial state.
	 * This method is useful when reusing the object or clearing data before creating a new entry.
	 */
	fun defaultAllFields() {
		bookmarkUrl = ""
		bookmarkName = ""
		bookmarkCreationDate = Date()
		bookmarkModifiedDate = Date()
		bookmarkThumbFilePath = ""
		bookmarkDescription = ""
		bookmarkTags = ArrayList()
		bookmarkFavorite = false
		bookmarkFolder = ""
		bookmarkAccessCount = 0
		bookmarkLastAccessed = ""
		bookmarkRating = 0.0f
		bookmarkPriority = 0
		bookmarkArchived = false
		bookmarkIcon = ""
		bookmarkNotes = ""
		bookmarkOwner = ""
		bookmarkSharedWith = ArrayList()
	}
}
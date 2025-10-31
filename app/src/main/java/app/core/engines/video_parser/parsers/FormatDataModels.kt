package app.core.engines.video_parser.parsers

import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable

/**
 * Data class representing a video format with all its metadata.
 *
 * @property id Unique database identifier
 * @property downloadDataModelId Unique identifier for the associated download model in the system
 * @property isFromSocialMedia Indicates if the format is from a social media platform
 * @property formatId Unique identifier for the format
 * @property formatExtension File extension (mp4, webm, etc.)
 * @property formatResolution Video resolution (1080p, 720p, etc.)
 * @property formatFileSize Size of the format file as string representation
 * @property formatVcodec Video codec information
 * @property formatAcodec Audio codec information
 * @property formatTBR Total bitrate
 * @property formatProtocol Protocol used for streaming (http, https, etc.)
 * @property formatStreamingUrl Direct streaming URL if available
 */
@CompiledJson
@Entity
data class VideoFormat(
	@Id @JvmField @param:JsonAttribute(name = "id")
	var id: Long = 0L,

	@JvmField @param:JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelId: Long = -1L,

	@JvmField @param:JsonAttribute(name = "isFromSocialMedia")
	var isFromSocialMedia: Boolean = false,

	@JvmField @param:JsonAttribute(name = "formatId")
	var formatId: String = "",

	@JvmField @param:JsonAttribute(name = "formatExtension")
	var formatExtension: String = "",

	@JvmField @param:JsonAttribute(name = "formatResolution")
	var formatResolution: String = "",

	@JvmField @param:JsonAttribute(name = "formatFileSize")
	var formatFileSize: String = "",

	@JvmField @param:JsonAttribute(name = "formatVcodec")
	var formatVcodec: String = "",

	@JvmField @param:JsonAttribute(name = "formatAcodec")
	var formatAcodec: String = "",

	@JvmField @param:JsonAttribute(name = "formatTBR")
	var formatTBR: String = "",

	@JvmField @param:JsonAttribute(name = "formatProtocol")
	var formatProtocol: String = "",

	@JvmField @param:JsonAttribute(name = "formatStreamingUrl")
	var formatStreamingUrl: String = ""
) : Serializable

/**
 * Data class representing complete video information and metadata.
 *
 * @property id Unique database identifier
 * @property downloadDataModelId Unique identifier for the associated download model in the system
 * @property videoTitle Title of the video
 * @property videoThumbnailUrl URL of the video thumbnail image
 * @property videoThumbnailByReferer Whether thumbnail requires referer header for access
 * @property videoDescription Video description text
 * @property videoUrlReferer Referer URL required for video access
 * @property videoUrl Original source video URL
 * @property videoFormats List of available video formats and qualities
 * @property videoCookie Cookie string for authenticated requests
 * @property videoDuration Media playback duration in milliseconds/long format
 * @property videoCookieTempPath Temporary file path for cookie storage
 */
@CompiledJson
@Entity
data class VideoInfo(
	@Id @JvmField @param:JsonAttribute(name = "id")
	var id: Long = 0L,

	@JvmField @param:JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelId: Long = -1L,

	@JvmField @param:JsonAttribute(name = "videoTitle")
	var videoTitle: String? = null,

	@JvmField @param:JsonAttribute(name = "videoThumbnailUrl")
	var videoThumbnailUrl: String? = null,

	@JvmField @param:JsonAttribute(name = "videoThumbnailByReferer")
	var videoThumbnailByReferer: Boolean = false,

	@JvmField @param:JsonAttribute(name = "videoDescription")
	var videoDescription: String? = null,

	@JvmField @param:JsonAttribute(name = "videoUrlReferer")
	var videoUrlReferer: String? = null,

	@JvmField @param:JsonAttribute(name = "videoUrl")
	var videoUrl: String = "",

	@JvmField @param:JsonAttribute(name = "videoFormats")
	var videoFormats: List<VideoFormat> = emptyList(),

	@JvmField @param:JsonAttribute(name = "videoCookie")
	var videoCookie: String? = "",

	@JvmField @param:JsonAttribute(name = "videoDuration")
	var videoDuration: Long = 0L,

	@JvmField @param:JsonAttribute(name = "videoCookieTempPath")
	var videoCookieTempPath: String = ""
) : Serializable

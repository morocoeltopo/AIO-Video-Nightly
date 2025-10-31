package app.core.engines.downloader

import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable

/**
 * Represents metadata about a remote file used in the download system.
 *
 * This class is designed to hold all the essential information about a file
 * hosted on a remote server, without actually downloading the file contents.
 * It is serializable with DSL-JSON for easy storage, transfer, or caching.
 *
 * Key responsibilities and properties:
 * 1. **Access control & errors**
 *    - [isFileForbidden]: Indicates whether access to the file was denied.
 *    - [errorMessage]: Provides details about any failure encountered while fetching file info.
 *
 * 2. **File identification & integrity**
 *    - [fileName]: The name of the file, either extracted from headers or URL.
 *    - [fileSize]: Size of the file in bytes; -1 if unknown.
 *    - [fileChecksum]: Optional cryptographic checksum (e.g., SHA-256) for verifying file integrity.
 *
 * 3. **Download capabilities**
 *    - [isSupportsMultipart]: True if the server supports partial downloads for faster or parallel fetching.
 *    - [isSupportsResume]: True if the download can be resumed using range requests, ETag, or Last-Modified headers.
 *
 * This class is intended to be lightweight and easily transportable. It captures
 * only the metadata required for managing downloads, progress tracking, and integrity checks.
 */
@CompiledJson
@Entity
class RemoteFileInfo() : Serializable {

	@Id
	var id: Long = 0L

	/** True if the server forbids access to the file. */
	@JvmField @JsonAttribute(name = "isFileForbidden")
	var isFileForbidden: Boolean = false

	/** Error message explaining why the file info could not be retrieved, if any. */
	@JvmField @JsonAttribute(name = "errorMessage")
	var errorMessage: String = ""

	/** Name of the file, either extracted from headers or URL path. */
	@JvmField @JsonAttribute(name = "fileName")
	var fileName: String = ""

	/** Size of the file in bytes; -1 indicates unknown size. */
	@JvmField @JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L

	/** Optional cryptographic checksum of the file for integrity verification. */
	@JvmField @JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = ""

	/** True if the server allows partial downloads (multipart support). */
	@JvmField @JsonAttribute(name = "isSupportsMultipart")
	var isSupportsMultipart: Boolean = false

	/** True if the download can be resumed after interruption. */
	@JvmField @JsonAttribute(name = "isSupportsResume")
	var isSupportsResume: Boolean = false
}

package app.core.engines.downloader

import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute

@CompiledJson
class RemoteFileInfo() {
	@JsonAttribute(name = "isFileForbidden")
	var isFileForbidden: Boolean = false

	@JsonAttribute(name = "errorMessage")
	var errorMessage: String = ""

	@JsonAttribute(name = "fileName")
	var fileName: String = ""

	@JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L

	@JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = ""

	@JsonAttribute(name = "isSupportsMultipart")
	var isSupportsMultipart: Boolean = false

	@JsonAttribute(name = "isSupportsResume")
	var isSupportsResume: Boolean = false
}
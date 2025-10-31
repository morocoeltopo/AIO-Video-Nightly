package app.core.engines.downloader

import app.core.AIOApp
import app.core.engines.objectbox.ObjectBoxManager
import app.core.engines.settings.AIOSettings
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.VideoInfo
import io.objectbox.Box
import io.objectbox.BoxStore
import lib.process.LogHelperUtils

object DownloadModelsDBManager {
	private val logger = LogHelperUtils.from(javaClass)
	private val boxStore: BoxStore = getBoxStore()
	private val downloadBox: Box<DownloadDataModel> by lazy { boxStore.boxFor(DownloadDataModel::class.java) }
	private val videoInfoBox: Box<VideoInfo> by lazy { boxStore.boxFor(VideoInfo::class.java) }
	private val videoFormatBox: Box<VideoFormat> by lazy { boxStore.boxFor(VideoFormat::class.java) }
	private val remoteFileInfoBox: Box<RemoteFileInfo> by lazy { boxStore.boxFor(RemoteFileInfo::class.java) }
	private val settingsBox: Box<AIOSettings> by lazy { boxStore.boxFor(AIOSettings::class.java) }

	@JvmStatic
	fun getBoxStore(): BoxStore {
		return ObjectBoxManager.getBoxStore()
	}

	@JvmStatic
	fun saveDownloadWithRelationsInDB(downloadDataModel: DownloadDataModel): Long {
		boxStore.runInTx {
			// Save main entity first to get ID
			downloadBox.put(downloadDataModel)
			val downloadId = downloadDataModel.id

			// Save related entities with foreign key
			downloadDataModel.videoInfo?.let { videoInfo ->
				videoInfo.id = downloadId
				videoInfo.downloadDataModelId = downloadId
				videoInfoBox.put(videoInfo)
			}

			downloadDataModel.videoFormat?.let { videoFormat ->
				videoFormat.id = downloadId
				videoFormat.downloadDataModelId = downloadId
				videoFormatBox.put(videoFormat)
			}

			downloadDataModel.remoteFileInfo?.let { remoteFileInfo ->
				remoteFileInfo.id = downloadId
				remoteFileInfo.downloadDataModelId = downloadId
				remoteFileInfoBox.put(remoteFileInfo)
			}

			downloadDataModel.globalSettings.let { settings ->
				settings.id = downloadId
				settings.downloadDataModelId = downloadId
				settingsBox.put(settings)
			}
		}
		return downloadDataModel.id
	}

	@JvmStatic
	fun getDownloadWithRelations(downloadId: Long): DownloadDataModel? {
		val downloadDataModel = downloadBox[downloadId] ?: return null
		return assembleDownloadWithRelations(downloadDataModel)
	}

	@JvmStatic
	fun getAllDownloadsWithRelations(): List<DownloadDataModel> {
		val downloadDataModels = downloadBox.all
		return downloadDataModels.map { assembleDownloadWithRelations(it) }
	}

	@JvmStatic
	private fun assembleDownloadWithRelations(downloadDataModel: DownloadDataModel): DownloadDataModel {
		val downloadId = downloadDataModel.id

		// Query VideoInfo by downloadId
		val videoInfoQuery = videoInfoBox.query()
			.equal(VideoInfo_.downloadDataModelId, downloadId)
			.build()
		downloadDataModel.videoInfo = videoInfoQuery.findFirst()
		videoInfoQuery.close()

		// Query VideoFormat by downloadId
		val videoFormatQuery = videoFormatBox.query()
			.equal(VideoFormat_.downloadDataModelId, downloadId)
			.build()
		downloadDataModel.videoFormat = videoFormatQuery.findFirst()
		videoFormatQuery.close()

		// Query VideoFormat by downloadId
		val remoteFileInfoQuery = remoteFileInfoBox.query()
			.equal(RemoteFileInfo_.downloadDataModelId, downloadId)
			.build()
		downloadDataModel.remoteFileInfo = remoteFileInfoQuery.findFirst()
		remoteFileInfoQuery.close()

		// Query GlobalSettings by downloadId
		val aioSettingsQuery = settingsBox.query()
			.equal(AIOSettings_.downloadDataModelId, downloadId)
			.build()
		downloadDataModel.globalSettings = aioSettingsQuery.findFirst() ?: AIOApp.aioSettings
		aioSettingsQuery.close()

		return downloadDataModel
	}

	fun deleteDownloadWithRelations(downloadId: Long) {
		boxStore.runInTx {
			// Delete related entities first
			val videoInfoQuery = videoInfoBox.query()
				.equal(VideoInfo_.downloadDataModelId, downloadId)
				.build()
			videoInfoQuery.findIds().forEach { videoInfoBox.remove(it) }
			videoInfoQuery.close()

			val videoFormatQuery = videoFormatBox.query()
				.equal(VideoFormat_.downloadDataModelId, downloadId)
				.build()
			videoFormatQuery.findIds().forEach { videoFormatBox.remove(it) }
			videoFormatQuery.close()

			val remoteFileInfoQuery = remoteFileInfoBox.query()
				.equal(RemoteFileInfo_.downloadDataModelId, downloadId)
				.build()
			remoteFileInfoQuery.findIds().forEach { remoteFileInfoBox.remove(it) }
			remoteFileInfoQuery.close()

			val settingsQuery = settingsBox.query()
				.equal(GlobalSettings_.downloadDataModelId, downloadId)
				.build()
			settingsQuery.findIds().forEach { settingsBox.remove(it) }
			settingsQuery.close()

			// Delete main entity
			downloadBox.remove(downloadId)
		}
	}

}
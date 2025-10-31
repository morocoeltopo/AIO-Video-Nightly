package app.core.engines.downloader

import app.core.AIOApp
import app.core.engines.objectbox.ObjectBoxManager
import app.core.engines.settings.AIOSettings
import app.core.engines.settings.AIOSettings_
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormat_
import app.core.engines.video_parser.parsers.VideoInfo
import app.core.engines.video_parser.parsers.VideoInfo_
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
			val downloadId = downloadBox.put(downloadDataModel)

			// Save related entities with foreign key
			downloadDataModel.videoInfo?.let { videoInfo ->
				videoInfo.downloadDataModelDBId = downloadId
				videoInfoBox.put(videoInfo)
			}

			downloadDataModel.videoFormat?.let { videoFormat ->
				videoFormat.downloadDataModelDBId = downloadId
				videoFormatBox.put(videoFormat)
			}

			downloadDataModel.remoteFileInfo?.let { remoteFileInfo ->
				remoteFileInfo.downloadDataModelDBId = downloadId
				remoteFileInfoBox.put(remoteFileInfo)
			}

			downloadDataModel.globalSettings.let { settings ->
				settings.downloadDataModelDBId = downloadId
				settingsBox.put(settings)
			}
		}
		return downloadDataModel.id
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
			.equal(VideoInfo_.downloadDataModelDBId, downloadId)
			.build()
		downloadDataModel.videoInfo = videoInfoQuery.findFirst()
		videoInfoQuery.close()

		// Query VideoFormat by downloadId
		val videoFormatQuery = videoFormatBox.query()
			.equal(VideoFormat_.downloadDataModelDBId, downloadId)
			.build()
		downloadDataModel.videoFormat = videoFormatQuery.findFirst()
		videoFormatQuery.close()

		// Query VideoFormat by downloadId
		val remoteFileInfoQuery = remoteFileInfoBox.query()
			.equal(RemoteFileInfo_.downloadDataModelDBId, downloadId)
			.build()
		downloadDataModel.remoteFileInfo = remoteFileInfoQuery.findFirst()
		remoteFileInfoQuery.close()

		// Query GlobalSettings by downloadId
		val aioSettingsQuery = settingsBox.query()
			.equal(AIOSettings_.downloadDataModelDBId, downloadId)
			.build()
		downloadDataModel.globalSettings = aioSettingsQuery.findFirst() ?: AIOApp.aioSettings
		aioSettingsQuery.close()

		return downloadDataModel
	}

	fun deleteDownloadWithRelations(downloadDataModel: DownloadDataModel) {
		boxStore.runInTx {
			val downloadModelDBID = downloadDataModel.id

			// Delete related entities first
			val videoInfoQuery = videoInfoBox.query()
				.equal(VideoInfo_.downloadDataModelDBId, downloadModelDBID)
				.build()
			videoInfoQuery.findIds().forEach { videoInfoBox.remove(it) }
			videoInfoQuery.close()

			val videoFormatQuery = videoFormatBox.query()
				.equal(VideoFormat_.downloadDataModelDBId, downloadModelDBID)
				.build()
			videoFormatQuery.findIds().forEach { videoFormatBox.remove(it) }
			videoFormatQuery.close()

			val remoteFileInfoQuery = remoteFileInfoBox.query()
				.equal(RemoteFileInfo_.downloadDataModelDBId, downloadModelDBID)
				.build()
			remoteFileInfoQuery.findIds().forEach { remoteFileInfoBox.remove(it) }
			remoteFileInfoQuery.close()

			val settingsQuery = settingsBox.query()
				.equal(AIOSettings_.downloadDataModelDBId, downloadModelDBID)
				.build()
			settingsQuery.findIds().forEach { settingsBox.remove(it) }
			settingsQuery.close()

			// Delete main entity
			downloadBox.remove(downloadModelDBID)
		}
	}

}
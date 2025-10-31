package app.core.engines.downloader

import app.core.AIOApp
import app.core.engines.downloader.DownloadModelsDBManager.assembleDownloadWithRelations
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

/**
 * Singleton manager for handling DownloadDataModel and its related entities in ObjectBox database.
 *
 * This manager provides a centralized interface for all database operations related to
 * download models and their associated entities (VideoInfo, VideoFormat, RemoteFileInfo, AIOSettings).
 *
 * Key Responsibilities:
 * - Saving download models with all related entities in transactional operations
 * - Loading download models with assembled relationships
 * - Deleting download models and their related entities
 * - Managing ObjectBox box instances for different entity types
 * - Ensuring data consistency through proper transaction handling
 *
 * Architecture:
 * - Uses lazy initialization for Box instances to optimize performance
 * - Implements transactional operations for data consistency
 * - Provides comprehensive error handling and logging
 * - Supports one-to-one relationships through foreign key associations
 *
 * Usage Pattern:
 * 1. Use saveDownloadWithRelationsInDB() to persist new downloads with all related data
 * 2. Use getAllDownloadsWithRelations() to retrieve all downloads with assembled relationships
 * 3. Use deleteDownloadWithRelations() to cleanly remove downloads and their related entities
 *
 * @see DownloadDataModel for the main entity definition
 * @see VideoInfo for video metadata entity
 * @see VideoFormat for video format entity
 * @see RemoteFileInfo for remote file information entity
 * @see AIOSettings for download-specific settings entity
 */
object DownloadModelsDBManager {

	private val logger = LogHelperUtils.from(javaClass)
	private val boxStore: BoxStore = getBoxStore()

	// Lazy-initialized Box instances for different entity types
	private val downloadBox: Box<DownloadDataModel> by lazy {
		boxStore.boxFor(DownloadDataModel::class.java).also {
			logger.d("DownloadDataModel box initialized")
		}
	}

	private val videoInfoBox: Box<VideoInfo> by lazy {
		boxStore.boxFor(VideoInfo::class.java).also {
			logger.d("VideoInfo box initialized")
		}
	}

	private val videoFormatBox: Box<VideoFormat> by lazy {
		boxStore.boxFor(VideoFormat::class.java).also {
			logger.d("VideoFormat box initialized")
		}
	}

	private val remoteFileInfoBox: Box<RemoteFileInfo> by lazy {
		boxStore.boxFor(RemoteFileInfo::class.java).also {
			logger.d("RemoteFileInfo box initialized")
		}
	}

	private val settingsBox: Box<AIOSettings> by lazy {
		boxStore.boxFor(AIOSettings::class.java).also {
			logger.d("AIOSettings box initialized")
		}
	}

	/**
	 * Retrieves the initialized BoxStore instance from ObjectBoxManager.
	 *
	 * @return The initialized BoxStore instance
	 * @throws IllegalStateException if ObjectBox is not properly initialized
	 *
	 * @see ObjectBoxManager.getBoxStore for the underlying implementation
	 */
	@JvmStatic
	fun getBoxStore(): BoxStore {
		logger.d("Retrieving BoxStore instance")
		return ObjectBoxManager.getBoxStore()
	}

	/**
	 * Saves a DownloadDataModel with all its related entities to the database in a single transaction.
	 *
	 * This method ensures data consistency by saving all related entities (VideoInfo, VideoFormat,
	 * RemoteFileInfo, AIOSettings) within the same transaction. If any part fails, the entire
	 * transaction is rolled back.
	 *
	 * Operation flow:
	 * 1. Saves the main DownloadDataModel entity first to obtain its ID
	 * 2. Sets foreign keys on all related entities using the obtained ID
	 * 3. Saves all related entities with proper association
	 *
	 * @param downloadDataModel The DownloadDataModel instance to save with all related entities
	 * @return The ID of the saved DownloadDataModel, or -1 if operation failed
	 *
	 * @throws Exception if database operations fail within the transaction
	 */
	@JvmStatic
	fun saveDownloadWithRelationsInDB(downloadDataModel: DownloadDataModel): Long {
		logger.d("Starting saveDownloadWithRelationsInDB for download: ${downloadDataModel.fileName}")
		try {
			boxStore.runInTx {
				// Save main entity first to get ID
				val downloadId = downloadBox.put(downloadDataModel)
				logger.d("Saved main DownloadDataModel with ID: $downloadId")

				// Save related entities with foreign key
				downloadDataModel.videoInfo?.let { videoInfo ->
					videoInfo.downloadDataModelDBId = downloadId
					videoInfoBox.put(videoInfo)
					logger.d("Saved related VideoInfo for download ID: $downloadId")
				} ?: logger.d("No VideoInfo found for download: ${downloadDataModel.fileName}")

				downloadDataModel.videoFormat?.let { videoFormat ->
					videoFormat.downloadDataModelDBId = downloadId
					videoFormatBox.put(videoFormat)
					logger.d("Saved related VideoFormat for download ID: $downloadId")
				}

				downloadDataModel.remoteFileInfo?.let { remoteFileInfo ->
					remoteFileInfo.downloadDataModelDBId = downloadId
					remoteFileInfoBox.put(remoteFileInfo)
					logger.d("Saved related RemoteFileInfo for download ID: $downloadId")
				} ?: logger.d("No RemoteFileInfo found for download: ${downloadDataModel.fileName}")

				downloadDataModel.globalSettings.let { settings ->
					settings.downloadDataModelDBId = downloadId
					settingsBox.put(settings)
					logger.d("Saved GlobalSettings for download ID: $downloadId")
				}
			}

			logger.d("Successfully completed saveDownloadWithRelationsInDB for download: ${downloadDataModel.fileName}")
			return downloadDataModel.id
		} catch (error: Exception) {
			logger.e("Error saving download and its relations: ${error.message}", error)
			return -1
		}
	}

	/**
	 * Retrieves all DownloadDataModel instances from the database with all their related entities assembled.
	 *
	 * This method performs queries for each related entity type and assembles them into complete
	 * DownloadDataModel objects. The assembly process includes:
	 * - VideoInfo metadata
	 * - VideoFormat information
	 * - RemoteFileInfo details
	 * - AIOSettings (falls back to global app settings if not found)
	 *
	 * @return List of fully assembled DownloadDataModel instances, or empty list if none found or error occurs
	 *
	 * @see assembleDownloadWithRelations for the detailed assembly process
	 */
	@JvmStatic
	fun getAllDownloadsWithRelations(): List<DownloadDataModel> {
		logger.d("Retrieving all downloads with relations from database")

		return try {
			val downloadDataModels = downloadBox.all
			logger.d("Found ${downloadDataModels.size} download models in database")

			val assembledDownloads = downloadDataModels.map { assembleDownloadWithRelations(it) }
			logger.d("Successfully assembled ${assembledDownloads.size} downloads with relations")

			assembledDownloads
		} catch (error: Exception) {
			logger.e("Failed to retrieve all downloads with relations from database", error)
			emptyList()
		}
	}

	/**
	 * Assembles a DownloadDataModel by querying and attaching all its related entities.
	 *
	 * This method performs individual queries for each related entity type using the
	 * downloadDataModel's ID as the foreign key. If AIOSettings are not found for the
	 * specific download, it falls back to the global application settings.
	 *
	 * @param downloadDataModel The base DownloadDataModel without related entities
	 * @return The fully assembled DownloadDataModel with all related entities attached
	 *
	 * @see AIOApp.aioSettings for the fallback global settings
	 */
	@JvmStatic
	private fun assembleDownloadWithRelations(downloadDataModel: DownloadDataModel): DownloadDataModel {
		val downloadId = downloadDataModel.id
		logger.d("Assembling relations for download ID: $downloadId")

		try {
			// Query VideoInfo by downloadId
			val videoInfoQuery = videoInfoBox.query()
				.equal(VideoInfo_.downloadDataModelDBId, downloadId)
				.build()
			downloadDataModel.videoInfo = videoInfoQuery.findFirst()
			videoInfoQuery.close()
			logger.d("VideoInfo ${if (downloadDataModel.videoInfo != null)
				"found" else "not found"} for download ID: $downloadId")

			// Query VideoFormat by downloadId
			val videoFormatQuery = videoFormatBox.query()
				.equal(VideoFormat_.downloadDataModelDBId, downloadId)
				.build()
			downloadDataModel.videoFormat = videoFormatQuery.findFirst()
			videoFormatQuery.close()
			logger.d("VideoFormat ${if (downloadDataModel.videoFormat != null)
				"found" else "not found"} for download ID: $downloadId")

			// Query RemoteFileInfo by downloadId
			val remoteFileInfoQuery = remoteFileInfoBox.query()
				.equal(RemoteFileInfo_.downloadDataModelDBId, downloadId)
				.build()
			downloadDataModel.remoteFileInfo = remoteFileInfoQuery.findFirst()
			remoteFileInfoQuery.close()
			logger.d("RemoteFileInfo ${if (downloadDataModel.remoteFileInfo != null) 
				"found" else "not found"} for download ID: $downloadId")

			// Query AIOSettings by downloadId with fallback to global settings
			val aioSettingsQuery = settingsBox.query()
				.equal(AIOSettings_.downloadDataModelDBId, downloadId)
				.build()
			downloadDataModel.globalSettings = aioSettingsQuery.findFirst() ?: AIOApp.aioSettings.also {
				logger.d("Using global AIOSettings as fallback for download ID: $downloadId")
			}
			aioSettingsQuery.close()
			logger.d("AIOSettings configured for download ID: $downloadId")

			logger.d("Successfully assembled all relations for download ID: $downloadId")
		} catch (error: Exception) {
			logger.e("Failed to assemble relations for download ID: $downloadId", error)
			// Ensure we at least have global settings as fallback
			downloadDataModel.globalSettings = AIOApp.aioSettings
		}

		return downloadDataModel
	}

	/**
	 * Deletes a DownloadDataModel and all its related entities from the database in a single transaction.
	 *
	 * This method ensures clean removal of all data associated with a download by:
	 * 1. Deleting all related entities (VideoInfo, VideoFormat, RemoteFileInfo, AIOSettings) first
	 * 2. Deleting the main DownloadDataModel entity last
	 * 3. Performing all operations within a single transaction for data consistency
	 *
	 * @param downloadDataModel The DownloadDataModel instance to delete along with all its relations
	 *
	 * @throws Exception if database operations fail within the transaction
	 */
	fun deleteDownloadWithRelations(downloadDataModel: DownloadDataModel) {
		val downloadModelDBID = downloadDataModel.id
		logger.d("Starting deletion of download with ID: $downloadModelDBID and all its relations")

		try {
			boxStore.runInTx {
				logger.d("Transaction started for deleting download ID: $downloadModelDBID")

				// Delete related entities first
				val videoInfoQuery = videoInfoBox.query()
					.equal(VideoInfo_.downloadDataModelDBId, downloadModelDBID)
					.build()
				val videoInfoIds = videoInfoQuery.findIds()
				videoInfoIds.forEach { videoInfoBox.remove(it) }
				videoInfoQuery.close()
				logger.d("Deleted ${videoInfoIds.size} VideoInfo records for download ID: $downloadModelDBID")

				val videoFormatQuery = videoFormatBox.query()
					.equal(VideoFormat_.downloadDataModelDBId, downloadModelDBID)
					.build()
				val videoFormatIds = videoFormatQuery.findIds()
				videoFormatIds.forEach { videoFormatBox.remove(it) }
				videoFormatQuery.close()
				logger.d("Deleted ${videoFormatIds.size} VideoFormat records for download ID: $downloadModelDBID")

				val remoteFileInfoQuery = remoteFileInfoBox.query()
					.equal(RemoteFileInfo_.downloadDataModelDBId, downloadModelDBID)
					.build()
				val remoteFileInfoIds = remoteFileInfoQuery.findIds()
				remoteFileInfoIds.forEach { remoteFileInfoBox.remove(it) }
				remoteFileInfoQuery.close()
				logger.d("Deleted ${remoteFileInfoIds.size} RemoteFileInfo records for download ID: $downloadModelDBID")

				val settingsQuery = settingsBox.query()
					.equal(AIOSettings_.downloadDataModelDBId, downloadModelDBID)
					.build()
				val settingsIds = settingsQuery.findIds()
				settingsIds.forEach { settingsBox.remove(it) }
				settingsQuery.close()
				logger.d("Deleted ${settingsIds.size} AIOSettings records for download ID: $downloadModelDBID")

				// Delete main entity last
				downloadBox.remove(downloadModelDBID)
				logger.d("Successfully deleted main DownloadDataModel with ID: $downloadModelDBID")

				logger.d("Completed deletion of download ID: $downloadModelDBID with all relations")
			}
		} catch (error: Exception) {
			logger.e("Failed to delete download with ID: $downloadModelDBID and its relations", error)
		}
	}
}
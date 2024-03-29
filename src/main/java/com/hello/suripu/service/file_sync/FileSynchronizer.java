package com.hello.suripu.service.file_sync;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.google.common.base.Joiner;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.db.FileInfoDAO;
import com.hello.suripu.core.db.FileManifestDAO;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.models.FileInfo;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileSynchronizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSynchronizer.class);

    private final FileInfoDAO fileInfoSenseOneDAO;
    private final FileInfoDAO fileInfoSenseOneFiveDAO;

    private final FileManifestDAO fileManifestDAO;

    private final AmazonS3 s3Signer;

    // FileInfo.id -> FileDownload (contains presigned URL)
    private final Cache<String, FileSync.FileManifest.FileDownload> fileDownloadCache;

    // How long the new presigned S3 URL should exist
    private final Long presignedUrlExpirationMinutes;

    private FileSynchronizer(final FileInfoDAO fileInfoSenseOneDAO,
                             final FileInfoDAO fileInfoSenseOneFiveDAO,
                             final FileManifestDAO fileManifestDAO,
                             final AmazonS3 s3Signer,
                             final Cache<String, FileSync.FileManifest.FileDownload> fileDownloadCache,
                             final Long presignedUrlExpirationMinutes)
    {
        this.fileInfoSenseOneDAO = fileInfoSenseOneDAO;
        this.fileInfoSenseOneFiveDAO = fileInfoSenseOneFiveDAO;
        this.fileManifestDAO = fileManifestDAO;
        this.s3Signer = s3Signer;
        this.fileDownloadCache = fileDownloadCache;
        this.presignedUrlExpirationMinutes = presignedUrlExpirationMinutes;
    }

    /**
     * @param fileInfoSenseOneDAO DAO for getting FileInfo so we know what files Sense should have. (Version 1.0)
     * @param fileInfoSenseOneFiveDAO same as above but for 1.5.
     * @param fileManifestDAO DAO for saving FileManifests from Sense so we know what files Sense does have.
     * @param s3Signer S3 client for generating presigned URLs
     * @param fileDownLoadCacheExpirationMinutes Time in minutes to keep FileDownload objects in cache (for generating
     *                                           response FileManifest). This cache is reused for all Senses, and is per file.
     * @param presignedUrlExpirationMinutes How long the presigned URL should stay around in minutes.
     *                                      Must be greater than fileDownLoadCacheExpirationMinutes.
     * @return New FileSynchronizer object
     */
    public static FileSynchronizer create(final FileInfoDAO fileInfoSenseOneDAO,
                                          final FileInfoDAO fileInfoSenseOneFiveDAO,
                                          final FileManifestDAO fileManifestDAO,
                                          final AmazonS3 s3Signer,
                                          final Long fileDownLoadCacheExpirationMinutes,
                                          final Long presignedUrlExpirationMinutes)
    {
        if (presignedUrlExpirationMinutes < fileDownLoadCacheExpirationMinutes) {
            throw new IllegalArgumentException("presignedUrlExpirationMinutes cannot be less than fileDownLoadCacheExpirationMinutes");
        }
        final Cache<String, FileSync.FileManifest.FileDownload> cache = CacheBuilder.newBuilder()
                .expireAfterWrite(fileDownLoadCacheExpirationMinutes, TimeUnit.MINUTES)
                .build();
        return new FileSynchronizer(fileInfoSenseOneDAO, fileInfoSenseOneFiveDAO, fileManifestDAO, s3Signer, cache, presignedUrlExpirationMinutes);
    }

    /**
     * @param senseId ID of the Sense
     * @param fileManifest FileManifest reported by this Sense
     * @return Response FileManifest that Sense should have
     */
    public FileSync.FileManifest synchronizeFileManifest(final String senseId,
                                                         final FileSync.FileManifest fileManifest,
                                                         final Boolean fileDownloadsEnabled,
                                                         final HardwareVersion hardwareVersion)
    {
        logErrors(fileManifest);
        fileManifestDAO.updateManifest(senseId, fileManifest);
        final FileSync.FileManifest responseManifest = getResponseManifest(senseId, fileManifest, hardwareVersion, fileDownloadsEnabled);
        logManifestChanges(senseId, responseManifest);
        return responseManifest;
    }

    /**
     * Convert a FileInfo to a FileDownload. Generates a presigned URL.
     * @throws URISyntaxException
     */
    private FileSync.FileManifest.FileDownload toFileDownload(final FileInfo fileInfo)
            throws URISyntaxException, DecoderException {
        final URI uri = new URI(fileInfo.uri); // s3://mybucket/my/key.ext
        final String s3Bucket = uri.getHost(); // mybucket
        final String s3Key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();    // my/key.ext

        final Date expiration = new java.util.Date();
        long msec = expiration.getTime();
        msec += 1000 * 60 * presignedUrlExpirationMinutes;
        expiration.setTime(msec);

        final URL presignedUrl = s3Signer.generatePresignedUrl(s3Bucket, s3Key, expiration, HttpMethod.GET);
        final String url;
        if (presignedUrl.getQuery() == null) {
            url = presignedUrl.getPath();
        } else {
            url = presignedUrl.getPath() + "?" + presignedUrl.getQuery();
        }
        final String host = presignedUrl.getHost(); // No scheme

        final int sep = fileInfo.path.lastIndexOf("/");
        final int pathStart = fileInfo.path.startsWith("/") ? 1 : 0; // Don't include leading slash, if present
        final String fileName = fileInfo.path.substring(sep+1); // just the name/extension
        final String filePath = fileInfo.path.substring(pathStart, sep); // just the file path, not including leading or trailing slashes

        final ByteString sha = ByteString.copyFrom(Hex.decodeHex(fileInfo.sha.toCharArray()));

        return FileSync.FileManifest.FileDownload.newBuilder()
                .setHost(host)
                .setUrl(url)
                .setSdCardFilename(fileName)
                .setSdCardPath(filePath)
                .setSha1(sha)
                .build();
    }

    private static String cacheKey(final Long fileInfoId, final HardwareVersion hardwareVersion) {
        return String.format("%s_%s", String.valueOf(hardwareVersion.value), String.valueOf(fileInfoId));
    }

    /**
     * Get the FileDownload from cache, or generate it.
     * @throws URISyntaxException
     */
    private FileSync.FileManifest.FileDownload fileDownloadFromCache(final String cacheKey, final FileInfo fileInfo)
            throws URISyntaxException, DecoderException
    {
        try {
            return fileDownloadCache.get(cacheKey, new Callable<FileSync.FileManifest.FileDownload>() {
                @Override
                public FileSync.FileManifest.FileDownload call() throws URISyntaxException, DecoderException {
                    return toFileDownload(fileInfo);
                }
            });
        } catch (ExecutionException e) {
            LOGGER.error("error=ExecutionException method=FileSynchronizer.fileDownloadFromCache file-info-id={} exception={}",
                    fileInfo.id, e);
            return toFileDownload(fileInfo);
        }
    }

    private List<FileSync.FileManifest.FileDownload> getFileDownloadsFromFileInfo(final List<FileInfo> fileInfoList, final HardwareVersion hardwareVersion) {

        final List<FileSync.FileManifest.FileDownload> downloads = new ArrayList<>(fileInfoList.size());

        for (final FileInfo fileInfo : fileInfoList) {
            try {
                final String cacheKey = cacheKey(fileInfo.id, hardwareVersion);
                downloads.add(fileDownloadFromCache(cacheKey, fileInfo));
            } catch (URISyntaxException e) {
                LOGGER.error("error=URISyntaxException uri={}", fileInfo.uri);
            } catch (DecoderException e) {
                LOGGER.error("error=DecoderException file-info-id={} sha={} uri={}", fileInfo.id, fileInfo.sha, fileInfo.uri);
            }
        }

        return downloads;
    }

    private FileSync.FileManifest getResponseManifest(final String senseId,
                                                      final FileSync.FileManifest requestManifest,
                                                      final HardwareVersion hardwareVersion,
                                                      final Boolean fileDownloadsEnabled)
    {
        final List<FileSync.FileManifest.FileDownload> expectedFileDownloads;

        // pick the right DAO based on hardware version
        final FileInfoDAO fileInfoDAO = (hardwareVersion.equals(HardwareVersion.SENSE_ONE)) ? fileInfoSenseOneDAO : fileInfoSenseOneFiveDAO;

        if (fileDownloadsEnabled) {
            LOGGER.debug("sense_id={} file_downloads_enabled={}", senseId, fileDownloadsEnabled);
            final List<FileInfo> expectedFileInfo = fileInfoDAO.getAll(requestManifest.getFirmwareVersion(), senseId);
            expectedFileDownloads = getFileDownloadsFromFileInfo(expectedFileInfo, hardwareVersion);
        } else {
            LOGGER.info("sense_id={} file_downloads_enabled={}", senseId, fileDownloadsEnabled);
            expectedFileDownloads = Collections.emptyList();
        }

        return FileManifestUtil.getResponseManifest(requestManifest, expectedFileDownloads);
    }

    private void logErrors(final FileSync.FileManifest manifest) {
        for (final FileSync.FileManifest.FileOperationError error : manifest.getErrorInfoList()) {
            LOGGER.error(FileManifestUtil.getErrorMessage(error));
        }
    }

    private void logManifestChanges(final String senseId, final FileSync.FileManifest manifest) {
        // Where update_file is false
        final List<String> unchangedPaths = Lists.newArrayList();

        // Where update_file is true but delete_file is false
        final List<String> updatedPaths = Lists.newArrayList();

        // Where update_file is true and delete_file is true
        final List<String> deletedPaths = Lists.newArrayList();

        for (final FileSync.FileManifest.File file : manifest.getFileInfoList()) {

            if (!file.hasDownloadInfo()) {
                continue;
            }

            final String path = FileManifestUtil.fullPath(file.getDownloadInfo());

            if (file.hasUpdateFile() && file.getUpdateFile()) {
                if (file.hasDeleteFile() && file.getDeleteFile()) {
                    deletedPaths.add(path);
                } else {
                    updatedPaths.add(path);
                }
            } else {
                unchangedPaths.add(path);
            }
        }

        final Joiner joiner = Joiner.on(":");
        LOGGER.info("sense-id={} unchanged-paths={} updated-paths={} deleted-paths={}",
                senseId, joiner.join(unchangedPaths), joiner.join(updatedPaths), joiner.join(deletedPaths));
    }

}

package com.hello.suripu.service.file_sync;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hello.suripu.api.input.FileSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileManifestUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileManifestUtil.class);

    public static String fullPath(final FileSync.FileManifest.FileDownload fileDownload) {
        return fileDownload.getSdCardPath() + "/" + fileDownload.getSdCardFilename();
    }

    private static Map<String, FileSync.FileManifest.FileDownload> getPathToFileDownloadMap(
            final List<FileSync.FileManifest.FileDownload> downloads)
    {
        final Map<String, FileSync.FileManifest.FileDownload> map = Maps.newHashMap();
        for (final FileSync.FileManifest.FileDownload fileDownload : downloads) {
            map.put(fullPath(fileDownload), fileDownload);
        }
        return map;
    }

    private static Boolean equalFileDownloads(final FileSync.FileManifest.FileDownload a, final FileSync.FileManifest.FileDownload b) {
        LOGGER.debug("method=equalFileDownloads a-path={} b-path={} a-filename={} b-filename={} a-sha={} b-sha={}",
                a.getSdCardPath(), b.getSdCardPath(),
                a.getSdCardFilename(), b.getSdCardFilename(),
                a.getSha1().toStringUtf8(), b.getSha1().toStringUtf8());
        return a.getSdCardPath().equals(b.getSdCardPath()) &&
                a.getSdCardFilename().equals(b.getSdCardFilename()) &&
                a.getSha1().equals(b.getSha1());
    }

    private static List<FileSync.FileManifest.File> newFileListFromReportedAndExpected(
            final List<FileSync.FileManifest.FileDownload> senseReportedFileDownloads,
            final List<FileSync.FileManifest.FileDownload> expectedFileDownloads)
    {
        final Map<String, FileSync.FileManifest.FileDownload> senseReportedMap = getPathToFileDownloadMap(senseReportedFileDownloads);
        final Map<String, FileSync.FileManifest.FileDownload> expectedMap = getPathToFileDownloadMap(expectedFileDownloads);

        final List<FileSync.FileManifest.File> files = Lists.newArrayList();

        // Additions/updates
        for (final Map.Entry<String, FileSync.FileManifest.FileDownload> expectedEntry : expectedMap.entrySet()) {
            final Boolean reportedBySense = senseReportedMap.containsKey(expectedEntry.getKey());
            final Boolean shouldUpdate = !reportedBySense ||
                    !equalFileDownloads(senseReportedMap.get(expectedEntry.getKey()), expectedEntry.getValue());

            if (shouldUpdate) {
                // Only add files that need updating
                files.add(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(expectedEntry.getValue())
                        .setUpdateFile(shouldUpdate)
                        .setDeleteFile(false)
                        .build());
                break; // Only return 1 file update at a time to limit the response size.
            }

        }

        // TODO delete files once we have a good mechanism for determining that a file should be deleted

        return files;
    }

    private static List<FileSync.FileManifest.FileDownload> getFileDownloadsFromFiles(final List<FileSync.FileManifest.File> files) {
        final List<FileSync.FileManifest.FileDownload> downloads = new ArrayList<>(files.size());
        for (final FileSync.FileManifest.File file : files) {
            if (file.hasDownloadInfo()) {
                downloads.add(file.getDownloadInfo());
            }
        }
        return downloads;
    }

    /**
     * @param requestManifest FileManifest uploaded by Sense.
     * @param expectedFileDownloads FileDownloads that should be present on the Sense.
     * @return The new FileManifest that sense should have based on the diff between the requestManifest and expectedFileDownloads.
     */
    static FileSync.FileManifest getResponseManifest(final FileSync.FileManifest requestManifest,
                                                     final List<FileSync.FileManifest.FileDownload> expectedFileDownloads)
    {
        final List<FileSync.FileManifest.FileDownload> reportedFileDownloads = getFileDownloadsFromFiles(requestManifest.getFileInfoList());

        final List<FileSync.FileManifest.File> newFiles = newFileListFromReportedAndExpected(reportedFileDownloads, expectedFileDownloads);

        return FileSync.FileManifest.newBuilder()
                .addAllFileInfo(newFiles)
                .setSenseId(requestManifest.getSenseId())
                .build();
    }


    static String getErrorMessage(final FileSync.FileManifest.FileOperationError error) {
        return String.format("filename=%s err_type=%s err_code=%s",
                error.getFilename(), error.getErrType(), error.getErrCode());
    }
}

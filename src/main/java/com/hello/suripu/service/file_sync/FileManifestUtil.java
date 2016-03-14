package com.hello.suripu.service.file_sync;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hello.suripu.api.input.FileSync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileManifestUtil {

    private static String fullPath(final FileSync.FileManifest.FileDownload fileDownload) {
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
            final Boolean shouldUpdate = !senseReportedMap.containsKey(expectedEntry.getKey()) ||
                    !equalFileDownloads(senseReportedMap.get(expectedEntry.getKey()), expectedEntry.getValue());
            files.add(FileSync.FileManifest.File.newBuilder()
                    .setDownloadInfo(expectedEntry.getValue())
                    .setUpdateFile(shouldUpdate)
                    .setDeleteFile(false)
                    .build());
        }

        // another loop for deletes
        for (final Map.Entry<String, FileSync.FileManifest.FileDownload> reportedEntry : senseReportedMap.entrySet()) {
            if (!expectedMap.containsKey(reportedEntry.getKey())) {
                // Not expected, so Sense should delete it.
                files.add(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(reportedEntry.getValue())
                        .setUpdateFile(true)
                        .setDeleteFile(true)
                        .build());
            }
        }

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

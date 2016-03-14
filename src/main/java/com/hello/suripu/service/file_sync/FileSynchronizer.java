package com.hello.suripu.service.file_sync;

import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.db.FileInfoDAO;
import com.hello.suripu.core.db.FileManifestDAO;
import com.hello.suripu.core.models.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileSynchronizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSynchronizer.class);

    final FileInfoDAO fileInfoDAO;
    final FileManifestDAO fileManifestDAO;

    private FileSynchronizer(final FileInfoDAO fileInfoDAO, final FileManifestDAO fileManifestDAO) {
        this.fileInfoDAO = fileInfoDAO;
        this.fileManifestDAO = fileManifestDAO;
    }

    public static FileSynchronizer create(final FileInfoDAO fileInfoDAO, final FileManifestDAO fileManifestDAO) {
        return new FileSynchronizer(fileInfoDAO, fileManifestDAO);
    }

    /**
     * @param senseId ID of the Sense
     * @param fileManifest FileManifest reported by this Sense
     * @return Response FileManifest that Sense should have
     */
    public FileSync.FileManifest synchronizeFileManifest(final String senseId, final FileSync.FileManifest fileManifest) {
        logErrors(fileManifest);
        fileManifestDAO.updateManifest(senseId, fileManifest);
        return getResponseManifest(senseId, fileManifest);
    }

    private static FileSync.FileManifest.FileDownload toFileDownload(final FileInfo fileInfo) throws URISyntaxException {
        final URI uri = new URI(fileInfo.uri);
        final String host = uri.getHost(); // No scheme
        final String url = uri.getPath(); // just the url path

        final int sep = fileInfo.path.lastIndexOf("/");
        final int pathStart = fileInfo.path.startsWith("/") ? 1 : 0; // Don't include leading slash, if present
        final String fileName = fileInfo.path.substring(sep+1); // just the name/extension
        final String filePath = fileInfo.path.substring(pathStart, sep); // just the file path, not including leading or trailing slashes

        final ByteString sha = ByteString.copyFromUtf8(fileInfo.sha);

        return FileSync.FileManifest.FileDownload.newBuilder()
                .setHost(host)
                .setUrl(url)
                .setSdCardFilename(fileName)
                .setSdCardPath(filePath)
                .setSha1(sha)
                .build();
    }

    private static List<FileSync.FileManifest.FileDownload> getFileDownloadsFromFileInfo(final List<FileInfo> fileInfoList) {

        final List<FileSync.FileManifest.FileDownload> downloads = new ArrayList<>(fileInfoList.size());

        for (final FileInfo fileInfo : fileInfoList) {
            try {
                downloads.add(toFileDownload(fileInfo));
            } catch (URISyntaxException e) {
                LOGGER.error("error=URISyntaxException uri={}", fileInfo.uri);
            }
        }

        return downloads;
    }

    private FileSync.FileManifest getResponseManifest(final String senseId, final FileSync.FileManifest requestManifest) {
        final List<FileInfo> expectedFileInfo = fileInfoDAO.getAll(requestManifest.getFirmwareVersion(), senseId);
        final List<FileSync.FileManifest.FileDownload> expectedFileDownloads = getFileDownloadsFromFileInfo(expectedFileInfo);
        return FileManifestUtil.getResponseManifest(requestManifest, expectedFileDownloads);
    }

    private void logErrors(final FileSync.FileManifest manifest) {
        for (final FileSync.FileManifest.FileOperationError error : manifest.getErrorInfoList()) {
            LOGGER.error(FileManifestUtil.getErrorMessage(error));
        }
    }

}

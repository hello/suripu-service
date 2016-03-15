package com.hello.suripu.service.file_sync;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.db.FileInfoDAO;
import com.hello.suripu.core.db.FileManifestDAO;
import com.hello.suripu.core.models.FileInfo;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileSynchronizerTest {

    FileInfoDAO fileInfoDAO;
    FileManifestDAO fileManifestDAO;
    FileSynchronizer fileSynchronizer;

    @Before
    public void setUp() {
        fileInfoDAO = Mockito.mock(FileInfoDAO.class);
        fileManifestDAO = Mockito.mock(FileManifestDAO.class);
        fileSynchronizer = FileSynchronizer.create(fileInfoDAO, fileManifestDAO);
    }



    @Test
    public void testSynchronizeFileManifestDeleteAllDownloads() throws Exception {
        final Integer firmwareVersion = 5;
        final String senseId = "sense";

        final String fileName = "file";
        final String path = "path";
        final String url = "url";
        final String host = "host";
        final ByteString sha = ByteString.copyFromUtf8("sha");
        final FileSync.FileManifest manifest = FileSync.FileManifest.newBuilder()
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardPath(path)
                                .setSdCardFilename(fileName)
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .build())
                .setSenseId(senseId)
                .setFirmwareVersion(firmwareVersion)
                .build();
        final List<FileInfo> fileInfoList = ImmutableList.of();

        Mockito.when(fileManifestDAO.updateManifest(Mockito.anyString(), Mockito.eq(manifest))).thenReturn(Optional.of(manifest));
        Mockito.when(fileInfoDAO.getAll(firmwareVersion, senseId)).thenReturn(fileInfoList);

        final FileSync.FileManifest responseManifest = fileSynchronizer.synchronizeFileManifest(senseId, manifest);
        assertThat(responseManifest.getFileInfoCount(), is(1));
        assertThat(responseManifest.getFileInfoList(), contains(FileSync.FileManifest.File.newBuilder()
                .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardPath(path)
                        .setSdCardFilename(fileName)
                        .setSha1(sha)
                        .setUrl(url)
                        .setHost(host)
                        .build())
                .setUpdateFile(true)
                .setDeleteFile(true)
                .build()));
    }

    @Test
    public void testSynchronizeFileManifest() throws Exception  {
        final Integer firmwareVersion = 5;
        final String senseId = "sense";
        
        final FileInfo noLeadingSlash = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(1L)
                .withIsPublic(true)
                .withName("noLeadingSlash")
                .withPath("path/noLeadingSlash")
                .withPreviewUri("preview")
                .withSha("noLeadingSlash")
                .withUri("http://localhost/noLeadingSlash")
                .withFirmwareVersion(1)
                .build();
        final FileInfo leadingSlash = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(1L)
                .withIsPublic(true)
                .withName("leadingSlash")
                .withPath("/path/to/leadingSlash")
                .withPreviewUri("preview")
                .withSha("leadingSlash")
                .withUri("http://localhost/leadingSlash")
                .withFirmwareVersion(1)
                .build();

        final List<FileInfo> fileInfoList = ImmutableList.of(noLeadingSlash, leadingSlash);

        final FileSync.FileManifest manifest = FileSync.FileManifest.newBuilder()
                .setSenseId(senseId)
                .setFirmwareVersion(firmwareVersion)
                .build();

        Mockito.when(fileManifestDAO.updateManifest(Mockito.anyString(), Mockito.eq(manifest))).thenReturn(Optional.of(manifest));
        Mockito.when(fileInfoDAO.getAll(firmwareVersion, senseId)).thenReturn(fileInfoList);

        final FileSync.FileManifest responseManifest = fileSynchronizer.synchronizeFileManifest(senseId, manifest);
        assertThat(responseManifest.getFileInfoCount(), is(2));

        assertThat(responseManifest.getFileInfoList(), containsInAnyOrder(
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardPath("path")
                                .setSdCardFilename("noLeadingSlash")
                                .setSha1(ByteString.copyFromUtf8(noLeadingSlash.sha))
                                .setUrl("/noLeadingSlash")
                                .setHost("localhost")
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardPath("path/to")
                                .setSdCardFilename("leadingSlash")
                                .setSha1(ByteString.copyFromUtf8(leadingSlash.sha))
                                .setUrl("/leadingSlash")
                                .setHost("localhost")
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .build()
        ));
    }
}
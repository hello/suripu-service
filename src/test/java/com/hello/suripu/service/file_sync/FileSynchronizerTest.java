package com.hello.suripu.service.file_sync;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.db.FileInfoDAO;
import com.hello.suripu.core.db.FileManifestDAO;
import com.hello.suripu.core.models.FileInfo;
import org.apache.commons.codec.binary.Hex;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.net.URL;
import java.util.Date;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileSynchronizerTest {

    private FileInfoDAO fileInfoDAO;
    private FileManifestDAO fileManifestDAO;
    private AmazonS3 s3Signer;

    private FileSynchronizer fileSynchronizer;

    @Before
    public void setUp() {
        fileInfoDAO = Mockito.mock(FileInfoDAO.class);
        fileManifestDAO = Mockito.mock(FileManifestDAO.class);
        s3Signer = Mockito.mock(AmazonS3.class);
        final Long PRESIGNED_URL_EXPIRATION_MINUTES = 0L;
        final Long FILE_DOWNLOAD_CACHE_EXPIRATION_MINUTES = 0L;
        fileSynchronizer = FileSynchronizer.create(
                fileInfoDAO, fileManifestDAO, s3Signer,
                FILE_DOWNLOAD_CACHE_EXPIRATION_MINUTES, PRESIGNED_URL_EXPIRATION_MINUTES);
    }

    @Test
    public void testSynchronizeFileManifest() throws Exception  {
        final Integer firmwareVersion = 5;
        final String senseId = "sense";
        final String leadingSlashSha = "b2";
        final String noLeadingSlashSha = "b3";

        final FileInfo noLeadingSlashFileInfo = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(1L)
                .withIsPublic(true)
                .withName("noLeadingSlash")
                .withPath("path/noLeadingSlash")
                .withPreviewUri("preview")
                .withSha(noLeadingSlashSha)
                .withUri("http://localhost/noLeadingSlash")
                .withFirmwareVersion(1)
                .build();
        final FileSync.FileManifest.FileDownload noLeadingSlashFileDownload = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardPath("path")
                .setSdCardFilename("noLeadingSlash")
                .setSha1(ByteString.copyFrom(Hex.decodeHex(noLeadingSlashFileInfo.sha.toCharArray())))
                .setUrl("/noLeadingSlash")
                .setHost("localhost")
                .build();

        final FileInfo leadingSlashFileInfo = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(1L)
                .withIsPublic(true)
                .withName("leadingSlash")
                .withPath("/path/to/leadingSlash")
                .withPreviewUri("preview")
                .withSha(leadingSlashSha)
                .withUri("http://localhost/leadingSlash")
                .withFirmwareVersion(1)
                .build();
        final FileSync.FileManifest.FileDownload leadingSlashFileDownload = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardPath("path/to")
                .setSdCardFilename("leadingSlash")
                .setSha1(ByteString.copyFrom(Hex.decodeHex(leadingSlashFileInfo.sha.toCharArray())))
                .setUrl("/leadingSlash")
                .setHost("localhost")
                .build();

        final List<FileInfo> fileInfoList = ImmutableList.of(noLeadingSlashFileInfo, leadingSlashFileInfo);

        final FileSync.FileManifest initialManifest = FileSync.FileManifest.newBuilder()
                .setSenseId(senseId)
                .setFirmwareVersion(firmwareVersion)
                .build();

        Mockito.when(fileManifestDAO.updateManifest(Mockito.eq(senseId), Mockito.eq(initialManifest))).thenReturn(Optional.<FileSync.FileManifest>absent());
        Mockito.when(fileInfoDAO.getAll(Mockito.anyInt(), Mockito.eq(senseId))).thenReturn(fileInfoList);
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("noLeadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/noLeadingSlash"));
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("leadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/leadingSlash"));

        final FileSync.FileManifest firstResponseManifest = fileSynchronizer.synchronizeFileManifest(senseId, initialManifest);
        assertThat(firstResponseManifest.getFileInfoCount(), is(1));
        assertThat(firstResponseManifest.getSenseId(), is(senseId));
        assertThat(firstResponseManifest.getFileInfo(0).getDeleteFile(), is(false));
        assertThat(firstResponseManifest.getFileInfo(0).getUpdateFile(), is(true));

        // Now pass in the response manifest that we got and ensure that we get the _other_ file in the next response.
        Mockito.when(fileManifestDAO.updateManifest(Mockito.eq(senseId), Mockito.eq(firstResponseManifest))).thenReturn(Optional.of(initialManifest));
        final FileSync.FileManifest secondResponseManifest = fileSynchronizer.synchronizeFileManifest(senseId, firstResponseManifest);
        assertThat(secondResponseManifest.getFileInfoCount(), is(1));
        assertThat(secondResponseManifest.getSenseId(), is(senseId));
        assertThat(secondResponseManifest.getFileInfo(0).getDeleteFile(), is(false));
        assertThat(secondResponseManifest.getFileInfo(0).getUpdateFile(), is(true));

        if (firstResponseManifest.getFileInfo(0).getDownloadInfo().getSdCardFilename().equals("leadingSlash")) {
            // Then the first response has the leading slash file, the second response doesn't.
            assertThat(firstResponseManifest.getFileInfo(0).getDownloadInfo(), is(leadingSlashFileDownload));
            assertThat(secondResponseManifest.getFileInfo(0).getDownloadInfo(), is(noLeadingSlashFileDownload));
        } else {
            // First response has the file without the leading slash, second has the leading slash.
            assertThat(firstResponseManifest.getFileInfo(0).getDownloadInfo(), is(noLeadingSlashFileDownload));
            assertThat(secondResponseManifest.getFileInfo(0).getDownloadInfo(), is(leadingSlashFileDownload));
        }

    }
}
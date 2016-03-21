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
import static org.hamcrest.Matchers.containsInAnyOrder;

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

        final FileInfo noLeadingSlash = FileInfo.newBuilder()
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
        final FileInfo leadingSlash = FileInfo.newBuilder()
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

        final List<FileInfo> fileInfoList = ImmutableList.of(noLeadingSlash, leadingSlash);

        final FileSync.FileManifest manifest = FileSync.FileManifest.newBuilder()
                .setSenseId(senseId)
                .setFirmwareVersion(firmwareVersion)
                .build();

        Mockito.when(fileManifestDAO.updateManifest(Mockito.anyString(), Mockito.eq(manifest))).thenReturn(Optional.of(manifest));
        Mockito.when(fileInfoDAO.getAll(firmwareVersion, senseId)).thenReturn(fileInfoList);
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("noLeadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/noLeadingSlash"));
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("leadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/leadingSlash"));

        final FileSync.FileManifest responseManifest = fileSynchronizer.synchronizeFileManifest(senseId, manifest);
        assertThat(responseManifest.getFileInfoCount(), is(2));

        assertThat(responseManifest.getFileInfoList(), containsInAnyOrder(
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardPath("path")
                                .setSdCardFilename("noLeadingSlash")
                                .setSha1(ByteString.copyFrom(Hex.decodeHex(noLeadingSlash.sha.toCharArray())))
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
                                .setSha1(ByteString.copyFrom(Hex.decodeHex(leadingSlash.sha.toCharArray())))
                                .setUrl("/leadingSlash")
                                .setHost("localhost")
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .build()
        ));
    }
}
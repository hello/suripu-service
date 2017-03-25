package com.hello.suripu.service.file_sync;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.db.FileInfoDAO;
import com.hello.suripu.core.db.FileInfoSenseOneDAO;
import com.hello.suripu.core.db.FileInfoSenseOneFiveDAO;
import com.hello.suripu.core.db.FileManifestDAO;
import com.hello.suripu.core.firmware.HardwareVersion;
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

    private FileInfoDAO fileInfoSenseOneDAO;
    private FileInfoDAO fileInfoSenseOneFiveDAO;
    private FileManifestDAO fileManifestDAO;
    private AmazonS3 s3Signer;

    private FileSynchronizer fileSynchronizer;

    @Before
    public void setUp() {
        fileInfoSenseOneDAO = Mockito.mock(FileInfoSenseOneDAO.class);
        fileInfoSenseOneFiveDAO = Mockito.mock(FileInfoSenseOneFiveDAO.class);
        fileManifestDAO = Mockito.mock(FileManifestDAO.class);
        s3Signer = Mockito.mock(AmazonS3.class);
        final Long PRESIGNED_URL_EXPIRATION_MINUTES = 0L;
        final Long FILE_DOWNLOAD_CACHE_EXPIRATION_MINUTES = 0L;
        fileSynchronizer = FileSynchronizer.create(
                fileInfoSenseOneDAO, fileInfoSenseOneFiveDAO, fileManifestDAO, s3Signer,
                FILE_DOWNLOAD_CACHE_EXPIRATION_MINUTES, PRESIGNED_URL_EXPIRATION_MINUTES);
    }

    @Test
    public void testSynchronizeFileManifest() throws Exception {
        final Integer firmwareVersion = 5;
        final String senseId = "sense";
        final String leadingSlashSha = "b2";
        final String noLeadingSlashSha = "b3";
        final HardwareVersion hardwareVersion = HardwareVersion.SENSE_ONE;

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
        Mockito.when(fileInfoSenseOneDAO.getAll(Mockito.anyInt(), Mockito.eq(senseId))).thenReturn(fileInfoList);
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("noLeadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/noLeadingSlash"));
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("leadingSlash"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/leadingSlash"));

        final FileSync.FileManifest firstResponseManifest = fileSynchronizer.synchronizeFileManifest(senseId, initialManifest, true, hardwareVersion);
        assertThat(firstResponseManifest.getFileInfoCount(), is(1));
        assertThat(firstResponseManifest.getSenseId(), is(senseId));
        assertThat(firstResponseManifest.getFileInfo(0).getDeleteFile(), is(false));
        assertThat(firstResponseManifest.getFileInfo(0).getUpdateFile(), is(true));

        // Now pass in the response manifest that we got and ensure that we get the _other_ file in the next response.
        Mockito.when(fileManifestDAO.updateManifest(Mockito.eq(senseId), Mockito.eq(firstResponseManifest))).thenReturn(Optional.of(initialManifest));
        final FileSync.FileManifest secondResponseManifest = fileSynchronizer.synchronizeFileManifest(senseId, firstResponseManifest, true, hardwareVersion);
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

    @Test
    public void testSynchronizeFileManifestWithHardwareVersion() throws Exception {
        final Integer firmwareVersion = 5;
        final String senseId = "sense";

        final FileSync.FileManifest initialManifest = FileSync.FileManifest.newBuilder()
                .setSenseId(senseId)
                .setFirmwareVersion(firmwareVersion)
                .build();

        final String senseOneUri = "http://localhost/sleep-tones-raw";
        final String senseOneSha = "dead";
        final FileInfo senseOneFileInfo = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(3L)
                .withIsPublic(true)
                .withName("IamOne")
                .withPath("path/senseOne")
                .withPreviewUri("preview")
                .withSha(senseOneSha)
                .withUri(senseOneUri)
                .withFirmwareVersion(1)
                .build();
        final FileSync.FileManifest.FileDownload senseOneDownload = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardPath("path")
                .setSdCardFilename("senseOne")
                .setSha1(ByteString.copyFrom(Hex.decodeHex(senseOneFileInfo.sha.toCharArray())))
                .setUrl("/sleep-tones-raw")
                .setHost("localhost")
                .build();

        final List<FileInfo> fileInfoOneList = ImmutableList.of(senseOneFileInfo);

        Mockito.when(fileInfoSenseOneDAO.getAll(Mockito.anyInt(), Mockito.eq(senseId))).thenReturn(fileInfoOneList);
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("sleep-tones-raw"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/sleep-tones-raw"));

        // return sense-one files
        final FileSync.FileManifest senseOneManifest = fileSynchronizer.synchronizeFileManifest(senseId, initialManifest, true, HardwareVersion.SENSE_ONE);
        assertThat(senseOneManifest.getFileInfoCount(), is(1));
        assertThat(senseOneManifest.getSenseId(), is(senseId));
        assertThat(senseOneManifest.getFileInfo(0).getDeleteFile(), is(false));
        assertThat(senseOneManifest.getFileInfo(0).getUpdateFile(), is(true));

        final FileSync.FileManifest.FileDownload firstFileDownload = senseOneManifest.getFileInfo(0).getDownloadInfo();
        assertThat(firstFileDownload.equals(senseOneDownload), is(true));

        final String senseOneFiveUri = "http://localhost/sleep-tones-raw-one-five";
        final String senseOneFiveSha = "deaddead";
        final FileInfo senseOneFiveFileInfo = FileInfo.newBuilder()
                .withFileType(FileInfo.FileType.SLEEP_SOUND)
                .withId(4L)
                .withIsPublic(true)
                .withName("IamOneFive")
                .withPath("path/senseOneFive")
                .withPreviewUri("preview")
                .withSha(senseOneFiveSha)
                .withUri(senseOneFiveUri)
                .withFirmwareVersion(15)
                .build();
        final FileSync.FileManifest.FileDownload senseOneFiveDownload = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardPath("path")
                .setSdCardFilename("senseOneFive")
                .setSha1(ByteString.copyFrom(Hex.decodeHex(senseOneFiveFileInfo.sha.toCharArray())))
                .setUrl("/sleep-tones-raw-one-five")
                .setHost("localhost")
                .build();
        final List<FileInfo> fileInfoOneFiveList = ImmutableList.of(senseOneFiveFileInfo);


        Mockito.when(fileInfoSenseOneFiveDAO.getAll(Mockito.anyInt(), Mockito.eq(senseId))).thenReturn(fileInfoOneFiveList);
        Mockito.when(s3Signer.generatePresignedUrl(Mockito.anyString(), Mockito.eq("sleep-tones-raw-one-five"), Mockito.any(Date.class), Mockito.any(HttpMethod.class)))
                .thenReturn(new URL("http", "localhost", 80, "/sleep-tones-raw-one-five"));

        // return one-five files
        final FileSync.FileManifest senseOneFiveManifest = fileSynchronizer.synchronizeFileManifest(senseId, initialManifest, true, HardwareVersion.SENSE_ONE_FIVE);
        assertThat(senseOneFiveManifest.getFileInfoCount(), is(1));
        assertThat(senseOneFiveManifest.getSenseId(), is(senseId));
        assertThat(senseOneFiveManifest.getFileInfo(0).getDeleteFile(), is(false));
        assertThat(senseOneFiveManifest.getFileInfo(0).getUpdateFile(), is(true));

        final FileSync.FileManifest.FileDownload secondFileDownload = senseOneFiveManifest.getFileInfo(0).getDownloadInfo();
        assertThat(secondFileDownload.equals(senseOneFiveDownload), is(true));

        // disable download
        final FileSync.FileManifest senseOneFiveManifestDisabled = fileSynchronizer.synchronizeFileManifest(senseId, initialManifest, false, HardwareVersion.SENSE_ONE_FIVE);
        assertThat(senseOneFiveManifestDisabled.getFileInfoCount(), is(0));
    }
}
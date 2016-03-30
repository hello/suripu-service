package com.hello.suripu.service.file_sync;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

/**
 * Created by jakepiccolo on 3/14/16.
 */
public class FileManifestUtilTest {

    @Test
    public void testGetResponseManifest() throws Exception {
        final String senseId = "sense";

        final String defaultDir = "dir";
        final String host = "host";
        final String url = "url";
        final ByteString sha = ByteString.copyFromUtf8("sha");

        final String fileStaysTheSame = "stays_the_same";
        final String fileShouldDelete = "should_be_deleted";
        final String fileWrongDirectory = "wrong_directory_should_delete";
        final String fileRightNameWrongSha = "dumb_sha";
        final String fileWrongHostRightName = "incorrect_host";

        final String fileShouldBeAdded = "should_be_added";

        final FileSync.FileManifest uploadedManifest = FileSync.FileManifest.newBuilder()
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileStaysTheSame)
                                .setSdCardPath(defaultDir)
                                .setHost(host)
                                .setUrl(url)
                                .setSha1(sha)
                                .build())
                        .build())
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileShouldDelete)
                                .setSdCardPath(defaultDir)
                                .setHost(host)
                                .setUrl(url)
                                .setSha1(sha)
                                .build())
                        .build())
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileWrongDirectory)
                                .setSdCardPath("some_other_dir")
                                .setHost(host)
                                .setUrl(url)
                                .setSha1(sha)
                                .build())
                        .build())
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileRightNameWrongSha)
                                .setSdCardPath(defaultDir)
                                .setHost(host)
                                .setUrl(url)
                                .setSha1(ByteString.copyFromUtf8("wrongshabro"))
                                .build())
                        .build())
                .addFileInfo(FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileWrongHostRightName)
                                .setSdCardPath(defaultDir)
                                .setHost("old_host")
                                .setUrl(url)
                                .setSha1(sha)
                                .build())
                        .build())
                .setSenseId(senseId)
                .build();

        final FileSync.FileManifest.FileDownload staysTheSame = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardFilename(fileStaysTheSame)
                .setSdCardPath(defaultDir)
                .setHost(host)
                .setUrl(url)
                .setSha1(sha)
                .build();
        final FileSync.FileManifest.FileDownload wrongDirectory = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardFilename(fileWrongDirectory)
                .setSdCardPath(defaultDir)
                .setHost(host)
                .setUrl(url)
                .setSha1(sha)
                .build();
        final FileSync.FileManifest.FileDownload rightNameWrongSha = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardFilename(fileRightNameWrongSha)
                .setSdCardPath(defaultDir)
                .setHost(host)
                .setUrl(url)
                .setSha1(sha)
                .build();
        final FileSync.FileManifest.FileDownload wrongHostRightName = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardFilename(fileWrongHostRightName)
                .setSdCardPath(defaultDir)
                .setHost(host)
                .setUrl(url)
                .setSha1(sha)
                .build();
        final FileSync.FileManifest.FileDownload shouldBeAdded = FileSync.FileManifest.FileDownload.newBuilder()
                .setSdCardFilename(fileShouldBeAdded)
                .setSdCardPath(defaultDir)
                .setHost(host)
                .setUrl(url)
                .setSha1(sha)
                .build();

        final FileSync.FileManifest withAddedFile = FileManifestUtil.getResponseManifest(uploadedManifest, ImmutableList.of(staysTheSame, shouldBeAdded));
        assertThat(withAddedFile.getSenseId(), is(senseId));
        assertThat(withAddedFile.getFileInfoCount(), is(1));
        assertThat(withAddedFile.getFileInfoList(), contains(FileSync.FileManifest.File.newBuilder()
                .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileShouldBeAdded)
                        .setSdCardPath(defaultDir)
                        .setSha1(sha)
                        .setUrl(url)
                        .setHost(host)
                        .build())
                .setUpdateFile(true)
                .setDeleteFile(false)
                .build()));
        assertThat(withAddedFile.getQueryDelay(), is(15));

        final FileSync.FileManifest withWrongDir = FileManifestUtil.getResponseManifest(uploadedManifest, ImmutableList.of(staysTheSame, wrongDirectory));
        assertThat(withWrongDir.getSenseId(), is(senseId));
        assertThat(withWrongDir.getFileInfoCount(), is(1));
        assertThat(withWrongDir.getFileInfoList(), contains(FileSync.FileManifest.File.newBuilder()
                .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileWrongDirectory)
                        .setSdCardPath(defaultDir)
                        .setSha1(sha)
                        .setUrl(url)
                        .setHost(host)
                        .build())
                .setUpdateFile(true)
                .setDeleteFile(false)
                .build()));
        assertThat(withWrongDir.getQueryDelay(), is(15));

        final FileSync.FileManifest withWrongSha = FileManifestUtil.getResponseManifest(uploadedManifest, ImmutableList.of(staysTheSame, rightNameWrongSha));
        assertThat(withWrongSha.getSenseId(), is(senseId));
        assertThat(withWrongSha.getFileInfoCount(), is(1));
        assertThat(withWrongSha.getFileInfoList(), contains(FileSync.FileManifest.File.newBuilder()
                .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileRightNameWrongSha)
                        .setSdCardPath(defaultDir)
                        .setSha1(sha)
                        .setUrl(url)
                        .setHost(host)
                        .build())
                .setUpdateFile(true)
                .setDeleteFile(false)
                .build()));
        assertThat(withWrongSha.getQueryDelay(), is(15));

        final FileSync.FileManifest withWrongHost = FileManifestUtil.getResponseManifest(uploadedManifest, ImmutableList.of(wrongHostRightName));
        // Wrong host is totally cool, so shouldn't be returned
        assertThat(withWrongHost.getSenseId(), is(senseId));
        assertThat(withWrongHost.getFileInfoCount(), is(0));
        assertThat(withWrongHost.getQueryDelay(), is(15));

        // Multiple files to download, only return the first
        final FileSync.FileManifest withAddedAndWrongDir = FileManifestUtil.getResponseManifest(uploadedManifest, ImmutableList.of(staysTheSame, shouldBeAdded, wrongDirectory));
        assertThat(withAddedAndWrongDir.getSenseId(), is(senseId));
        assertThat(withAddedAndWrongDir.getFileInfoCount(), is(1));
        assertThat(withAddedAndWrongDir.getFileInfoList(), contains(FileSync.FileManifest.File.newBuilder()
                .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileShouldBeAdded)
                        .setSdCardPath(defaultDir)
                        .setSha1(sha)
                        .setUrl(url)
                        .setHost(host)
                        .build())
                .setUpdateFile(true)
                .setDeleteFile(false)
                .build()));
        assertThat(withAddedAndWrongDir.getQueryDelay(), is(2));

        // Don't send any files if download pending
        final FileSync.FileManifest withDownloadPending = FileManifestUtil.getResponseManifest(
                FileSync.FileManifest.newBuilder(uploadedManifest).setFileStatus(FileSync.FileManifest.FileStatusType.DOWNLOAD_PENDING).build(),
                ImmutableList.of(staysTheSame, shouldBeAdded, wrongDirectory));
        assertThat(withDownloadPending.getQueryDelay(), is(2));
        assertThat(withDownloadPending.getFileInfoCount(), is(0));
    }

    @Test
    public void testGetErrorMessage() throws Exception {
        final FileSync.FileManifest.FileOperationError error = FileSync.FileManifest.FileOperationError.newBuilder()
                .setErrCode(100)
                .setErrType(FileSync.FileManifest.FileOperationError.ErrorType.NO_ERROR)
                .setFilename("thename")
                .build();
        final String message = FileManifestUtil.getErrorMessage(error);
        assertThat(message, containsString("filename=thename"));
        assertThat(message, containsString("err_code=100"));
        assertThat(message, containsString("err_type=NO_ERROR"));
    }
}
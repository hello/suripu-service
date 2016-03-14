package com.hello.suripu.service.file_sync;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

        final List<FileSync.FileManifest.FileDownload> expectedDownloads = ImmutableList.of(
                FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileStaysTheSame)
                        .setSdCardPath(defaultDir)
                        .setHost(host)
                        .setUrl(url)
                        .setSha1(sha)
                        .build(),
                FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileWrongDirectory)
                        .setSdCardPath(defaultDir)
                        .setHost(host)
                        .setUrl(url)
                        .setSha1(sha)
                        .build(),
                FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileRightNameWrongSha)
                        .setSdCardPath(defaultDir)
                        .setHost(host)
                        .setUrl(url)
                        .setSha1(sha)
                        .build(),
                FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileWrongHostRightName)
                        .setSdCardPath(defaultDir)
                        .setHost(host)
                        .setUrl(url)
                        .setSha1(sha)
                        .build(),
                FileSync.FileManifest.FileDownload.newBuilder()
                        .setSdCardFilename(fileShouldBeAdded)
                        .setSdCardPath(defaultDir)
                        .setHost(host)
                        .setUrl(url)
                        .setSha1(sha)
                        .build()
        );

        final FileSync.FileManifest responseManifest = FileManifestUtil.getResponseManifest(uploadedManifest, expectedDownloads);
        assertThat(responseManifest.getSenseId(), is(senseId));
        assertThat(responseManifest.getFileInfoCount(), is(7));
        assertThat(responseManifest.getFileInfoList(), containsInAnyOrder(
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                            .setSdCardFilename(fileStaysTheSame)
                            .setSdCardPath(defaultDir)
                            .setSha1(sha)
                            .setUrl(url)
                            .setHost(host)
                            .build())
                    .setUpdateFile(false)
                    .setDeleteFile(false)
                    .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileShouldDelete)
                                .setSdCardPath(defaultDir)
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(true)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileWrongDirectory)
                                .setSdCardPath(defaultDir)
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileWrongDirectory)
                                .setSdCardPath("some_other_dir")
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(true)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileRightNameWrongSha)
                                .setSdCardPath(defaultDir)
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .setUpdateFile(true)
                        .setDeleteFile(false)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
                        .setDownloadInfo(FileSync.FileManifest.FileDownload.newBuilder()
                                .setSdCardFilename(fileWrongHostRightName)
                                .setSdCardPath(defaultDir)
                                .setSha1(sha)
                                .setUrl(url)
                                .setHost(host)
                                .build())
                        .setUpdateFile(false)
                        .setDeleteFile(false)
                        .build(),
                FileSync.FileManifest.File.newBuilder()
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
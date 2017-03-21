package com.hello.suripu.service.utils;

import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;


/**
 * Created by ksg on 3/15/17
 */
public class FileShaCheckerTest {
    private static String NOCTURNE_SHA = "3fa21852f29d15b3d8d8eeb0d05c6c42b7ca9041";
    private static String BAD_NOCTURNE_SHA = "7a0047a707520c717a10cc22d4e5a1e75cf87ffa";
    private static String NOCTURNE_PATH = "/SLPTONES/ST012.RAW";

    private FileSync.FileManifest.FileDownload fileDownloadGood;
    private FileSync.FileManifest.FileDownload fileDownloadBad;

    @Before
    public void setUp() throws DecoderException {

        final String path = NOCTURNE_PATH;
        final int sep = path.lastIndexOf("/");
        final int pathStart = path.startsWith("/") ? 1 : 0; // Don't include leading slash, if present
        final String fileName = path.substring(sep+1); // just the name/extension
        final String filePath = path.substring(pathStart, sep); // just the file path, not including leading or trailing slashes

        final ByteString sha = ByteString.copyFrom(Hex.decodeHex(NOCTURNE_SHA.toCharArray()));

        fileDownloadGood = FileSync.FileManifest.FileDownload.newBuilder()
                .setHost("testing")
                .setUrl("testing_url")
                .setSdCardFilename(fileName)
                .setSdCardPath(filePath)
                .setSha1(sha)
                .build();

        final ByteString shaBad = ByteString.copyFrom(Hex.decodeHex(BAD_NOCTURNE_SHA.toCharArray()));

        fileDownloadBad = FileSync.FileManifest.FileDownload.newBuilder()
                .setHost("testing")
                .setUrl("testing_url")
                .setSdCardFilename(fileName)
                .setSdCardPath(filePath)
                .setSha1(shaBad)
                .build();

    }

    @Test
    public void test() throws DecoderException {
        final boolean result = FileShaChecker.checkOK(fileDownloadGood);
        assertThat(result, is(true));

        final boolean resultBad = FileShaChecker.checkOK(fileDownloadBad);
        assertThat(resultBad, is(false));

    }
}

package com.hello.suripu.service.utils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.service.file_sync.FileManifestUtil;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Created by ksg on 3/15/17
 */
public class FileShaChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileShaChecker.class);

    private static Map<String, ByteString> pathToShaOneMap;
    static {
        Map<String, String> tmpMap = Maps.newHashMap();
        tmpMap.put("SLPTONES/ST001.RAW", "7dd42ec7e55b00afbbcb2a8e129dc0fc573961eb");  // Brown Noise
        tmpMap.put("SLPTONES/ST002.RAW", "d30817227ce93708167be1022de1f1625853ffce");  // Cosmos
        tmpMap.put("SLPTONES/ST003.RAW", "777a25489420095ce034bd082d964fc47d9c7c78");  // Autumn Wind
        tmpMap.put("SLPTONES/ST004.RAW", "686ab4987f6014611b1bc18872364e76ab98277d");  // Fireside
        tmpMap.put("SLPTONES/ST005.RAW", "25f05b2302ae69c1a501989852497257a1cf31d7");  // Ocean Waves
        tmpMap.put("SLPTONES/ST006.RAW", "128cf3d664e39667e4eabff647f8ed0cc4edd109");  // Rainfall
        tmpMap.put("SLPTONES/ST007.RAW", "bd26a9cbbe9781852c5454706c1b04e742d72f2e");  // White Noise
        tmpMap.put("SLPTONES/ST008.RAW", "56ac9affc489328cd14bdd35bd0c256635ab0faa");  // Forest Creek
        tmpMap.put("SLPTONES/ST009.RAW", "a0a370f64cd543449f055f8ca666bfff40bf6620");  // Morpheus
        tmpMap.put("SLPTONES/ST010.RAW", "f7b36fb9c4ade09397ce3135b1bd0d2c3b0cfb12");  // Aura
        tmpMap.put("SLPTONES/ST011.RAW", "684dcf2842df76cf0409bf51c7e303bae92e25d0");  // Horizon
        tmpMap.put("SLPTONES/ST012.RAW", "3fa21852f29d15b3d8d8eeb0d05c6c42b7ca9041");  // Nocturne

        final Map<String, ByteString> secondMap = Maps.newHashMap();
        for (final String path : tmpMap.keySet()) {
            try {
                final ByteString sha = ByteString.copyFrom(Hex.decodeHex(tmpMap.get(path).toCharArray()));
                secondMap.put(path, sha);
            } catch (DecoderException e) {
                LOGGER.error("error=fail-to-convert-sha path={} sha={}", path, tmpMap.get(path));
            }
        }
        pathToShaOneMap = ImmutableMap.copyOf(secondMap);
    }


    /**
     * Check the list of files for SHA mismatch (sense 1.5 only), and log failures
     * @param senseId device external ID
     * @param fileList list of file manifest info
     */
    public static void checkFileSHAForSense1p5(final String senseId, final List<FileSync.FileManifest.File> fileList) {
        for (final FileSync.FileManifest.File fileInfo : fileList) {
            if (fileInfo.hasDownloadInfo()) {

                final FileSync.FileManifest.FileDownload downloadInfo = fileInfo.getDownloadInfo();
                if (!checkOK(downloadInfo)) {
                    final String shaString = Hex.encodeHexString(downloadInfo.getSha1().toByteArray());
                    LOGGER.error("error=file-corruption-2 sense_id={} path={} sense_sha={}", senseId,
                            FileManifestUtil.fullPath(downloadInfo),
                            shaString);
                }

            }
        }
    }

    static Boolean checkOK(final FileSync.FileManifest.FileDownload fileDownload) {
        final ByteString sha1 = fileDownload.getSha1();
        final String path = FileManifestUtil.fullPath(fileDownload);
        return !pathToShaOneMap.containsKey(path) || pathToShaOneMap.get(path).equals(sha1);
    }


}

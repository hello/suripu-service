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
        tmpMap.put("/SLPTONES/ST001.RAW", "645cc06458638cd4f76ee6600644c7c8816df128");  // Brown Noise
        tmpMap.put("/SLPTONES/ST002.RAW", "33f7b6ae44aa17be3d21a635e8ec025f49f5e0a8");  // Cosmos
        tmpMap.put("/SLPTONES/ST003.RAW", "9fad8359e4590701fbfa43553fff8698055ef18f");  // Autumn Wind
        tmpMap.put("/SLPTONES/ST004.RAW", "ee9319a6109926407e6e12facdb290d87eeff205");  // Fireside
        tmpMap.put("/SLPTONES/ST005.RAW", "41d2c560745f5d61eea8dce029f3574f549fc614");  // Ocean Waves
        tmpMap.put("/SLPTONES/ST006.RAW", "a7f6a62482fab455a7bdbf34a5674900811f1cd4");  // Rainfall
        tmpMap.put("/SLPTONES/ST007.RAW", "cd93de784b6fa8358ecab0f934ee108ad486cc1b");  // White Noise
        tmpMap.put("/SLPTONES/ST008.RAW", "7a0cb6a3a31420a2ce1c0e30273718fa04a8cb93");  // Forest Creek
        tmpMap.put("/SLPTONES/ST009.RAW", "bd1e7b0c174a5888c50f8d44287934721deefb38");  // Morpheus
        tmpMap.put("/SLPTONES/ST010.RAW", "71a7638f76d92696ba47d87a64b9f6fd8568004b");  // Aura
        tmpMap.put("/SLPTONES/ST011.RAW", "75a23b1c9fea199dcf27710564e7c5d1b9454a5f");  // Horizon
        tmpMap.put("/SLPTONES/ST012.RAW", "150267de2653815c39f7ed50b40208b8350e38b2");  // Nocturne

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
                    LOGGER.error("error=file-corruption sense_id={} path={} sha={}", senseId,
                            FileManifestUtil.fullPath(downloadInfo),
                            shaString);
                }

            }
        }
    }

    static Boolean checkOK(final FileSync.FileManifest.FileDownload fileDownload) {
        final ByteString sha1 = fileDownload.getSha1();
        final String path = String.format("/%s/%s", fileDownload.getSdCardPath(), fileDownload.getSdCardFilename());
        return !pathToShaOneMap.containsKey(path) || pathToShaOneMap.get(path).equals(sha1);
    }


}

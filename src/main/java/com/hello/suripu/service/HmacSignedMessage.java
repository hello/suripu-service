package com.hello.suripu.service;

import com.google.common.base.Optional;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HmacSignedMessage {

    private static final Logger LOGGER = LoggerFactory.getLogger(HmacSignedMessage.class);

    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

    /**
     * See http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/AuthJavaSampleHMACSignature.html
     */
    public static Optional<String> calculateRFC2104HMAC(final String data, final String key) {
        try {

            // get an hmac_sha1 key from the raw key bytes
            final SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), HMAC_SHA1_ALGORITHM);

            // get an hmac_sha1 Mac instance and initialize with the signing key
            final Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);

            // compute the hmac on input data bytes
            final byte[] rawHmac = mac.doFinal(data.getBytes());
            final String result = Hex.encodeHexString(rawHmac);
            return Optional.of(result);
        } catch (Exception e) {
            LOGGER.error("action=hmac error={}", e.getMessage());
        }
        return Optional.absent();
    }
}

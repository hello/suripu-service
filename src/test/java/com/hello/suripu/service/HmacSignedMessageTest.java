package com.hello.suripu.service;

import com.google.common.base.Optional;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class HmacSignedMessageTest {

    @Test
    public void testHmac() {

        final Optional<String> string = HmacSignedMessage.calculateRFC2104HMAC("hello", "whatever");
        final String hexDigest = "13960370f17799383e18da114a64c4042df19a18";
        // Derived from http://www.freeformatter.com/hmac-generator.html#ad-output
        assertThat(string.isPresent(), is(true));
        assertThat(hexDigest.equalsIgnoreCase(string.get()), is(true));
    }
}

package com.hello.suripu.service;

import com.hello.suripu.core.firmware.HardwareVersion;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class UtilTest {

    public static class TableTest {
        final String name;
        final String headerVersion;
        final HardwareVersion expectedHardwareVersion;

        public TableTest(final String name, final String headerVersion, final HardwareVersion expectedHardwareVersion) {
            this.name = name;
            this.headerVersion = headerVersion;
            this.expectedHardwareVersion = expectedHardwareVersion;
        }
    }

    @Test
    public void testCorrectHardwareHeader() {
        final String nullVersion = null;
        final String emptyVersion = "";
        final String invalidVersion = "aaaaa";
        final String version1 = "1";
        final String version4 = "4";


        final TableTest[] tests = new TableTest[]{
                new TableTest("nullVersion", null, HardwareVersion.SENSE_ONE),
                new TableTest("emptyVersion", "", HardwareVersion.SENSE_ONE),
                new TableTest("invalidVersion", "aaaaa", HardwareVersion.SENSE_ONE),
                new TableTest("version1", "1", HardwareVersion.SENSE_ONE),
                new TableTest("version4", "4", HardwareVersion.SENSE_ONE_FIVE),
        };

        for (final TableTest test : tests) {
            final HardwareVersion hw = Util.getHardwareVersion(test.headerVersion);
            assertThat(test.name, hw, equalTo(test.expectedHardwareVersion));
        }

    }
}

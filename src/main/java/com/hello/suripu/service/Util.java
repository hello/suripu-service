package com.hello.suripu.service;

import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.util.HelloHttpHeader;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedList;

/**
 * Created by pangwu on 5/8/14.
 */
public class Util {
    private static final Logger LOGGER = LoggerFactory.getLogger(Util.class);
    private static final String FIRMWARE_DEFAULT = "0";
    public static final String HW_VERSION = "X-Hello-Sense-HW";

    public static double getAverageSVM(LinkedList<TrackerMotion> buffer){
        double average = 0.0;
        for(TrackerMotion datum:buffer){
            average += datum.value;
        }

        return average / buffer.size();
    }

    public static DateTime roundTimestampToMinuteUTC(long timestamp){
        DateTime dateTimeUTC = new DateTime(timestamp, DateTimeZone.UTC);
        DateTime roundedDateTimeUTC = new DateTime(
                dateTimeUTC.getYear(),
                dateTimeUTC.getMonthOfYear(),
                dateTimeUTC.getDayOfMonth(),
                dateTimeUTC.getHourOfDay(),
                dateTimeUTC.getMinuteOfHour(),
                DateTimeZone.UTC
        );

        return roundedDateTimeUTC;
    }

    public static String getFWVersionFromHeader(final HttpServletRequest request, final String headerName) {
        if (headerName.equals(HelloHttpHeader.TOP_FW_VERSION) && request.getHeader(HelloHttpHeader.TOP_FW_VERSION) != null) {
            return request.getHeader(HelloHttpHeader.TOP_FW_VERSION);
        }
        //middle fw version is passed as hex string here and is a dec string in build_info.txt. Converting from hex string here.
        if (headerName.equals(HelloHttpHeader.MIDDLE_FW_VERSION) && request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION) != null) {
            try {

                if (Long.parseLong(request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION), 16) > Integer.MAX_VALUE) {
                    return FIRMWARE_DEFAULT;
                }
                
                return Integer.toString(Integer.parseInt(request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION), 16));
            } catch (Exception ex) {
                LOGGER.error("error=fw-header-format middle_fw_value='{}'", request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION), ex.getMessage());
            }
        }
        return FIRMWARE_DEFAULT;
    }

    public static HardwareVersion getHardwareVersionFromHeader(final HttpServletRequest request) {
        final String maybeNull = request.getHeader(HW_VERSION);
        return getHardwareVersion(maybeNull);
    }

    public static HardwareVersion getHardwareVersion(final String maybeHardwareVersion) {
        if(maybeHardwareVersion != null) {
            try {
                return HardwareVersion.fromInt(Integer.parseInt(maybeHardwareVersion));
            } catch (IllegalArgumentException e) {
                LOGGER.error("error=bad-hw-version header={}", maybeHardwareVersion);
            }
        }
        return HardwareVersion.SENSE_ONE;
    }
}

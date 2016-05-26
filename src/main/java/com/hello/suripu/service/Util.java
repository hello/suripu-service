package com.hello.suripu.service;

import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.core.util.HelloHttpHeader;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import javax.servlet.http.HttpServletRequest;

/**
 * Created by pangwu on 5/8/14.
 */
public class Util {
    private static final Logger LOGGER = LoggerFactory.getLogger(Util.class);
    private static final String FIRMWARE_DEFAULT = "0";

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
                return Integer.toString(Integer.parseInt(request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION), 16));
            } catch (Exception ex) {
                LOGGER.error("error=fw-header-format middle_fw_value='{}'", request.getHeader(HelloHttpHeader.MIDDLE_FW_VERSION), ex.getMessage());
            }
        }
        return FIRMWARE_DEFAULT;
    }
}

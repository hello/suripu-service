package com.hello.suripu.service.utils;

/**
 * Created by jakepiccolo on 4/20/16.
 */
public enum ServiceFeatureFlipper {


    FILE_DOWNLOAD_DISABLED("file_download_disabled"), // Blacklist for Senses that should not download files
    SENSE_SWAP_ENABLED("sense_swap_enabled"),
    SENSE_UPLOADS_KEYWORD_FEATURES("sense_uploads_keyword_features"),
    SERVER_ACCEPTS_KEYWORD_FEATURES("server_accepts_keyword_features"),
    IS_SENSE_ONE_FIVE_DVT_UNIT("is_sense_one_five_dvt_unit"),
    PRINT_ALARM_ACK("print_alarm_ack"),
    FUTURE_ALARM_ENABLED("future_alarm_enabled"), //logic fix - only look at future alarm when getting next alarm
    SMART_ALARM_SAFEGAURD("smart_alarm_safeguard"),
    DISABLED_SENSE("disabled_sense"), // for stolen senses
    FILE_DOWNLOAD_SENSE_1P5("file_download_sense_1p5"),
    NOTIFY_ON_PAIRING_CONFLICT("notify_on_pairing_conflict");

    private final String featureName;
    ServiceFeatureFlipper(final String name) {
        this.featureName = name;
    }

    public String getFeatureName() {
        return featureName;
    }
}

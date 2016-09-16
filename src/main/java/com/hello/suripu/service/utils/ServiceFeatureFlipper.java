package com.hello.suripu.service.utils;

/**
 * Created by jakepiccolo on 4/20/16.
 */
public enum ServiceFeatureFlipper {

    // Blacklist for Senses that should not download files
    FILE_DOWNLOAD_DISABLED("file_download_disabled"),
    SENSE_SWAP_ENABLED("sense_swap_enabled"),
    IS_SENSE_ONE_FIVE_DVT_UNIT("is_sense_one_five_dvt_unit");

    private final String featureName;
    ServiceFeatureFlipper(final String name) {
        this.featureName = name;
    }

    public String getFeatureName() {
        return featureName;
    }
}

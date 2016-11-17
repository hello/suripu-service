package com.hello.suripu.service.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * Created by benjo on 11/17/16.
 */
public class FirehoseConfiguration {

    @Valid
    @NotNull
    @JsonProperty("endpoint")
    private String endpoint;
    public String getEndpoint() {
        return endpoint;
    }

    @Valid
    @NotNull
    @JsonProperty("stream_name")
    private String streamName;
    public String getStreamName() {
        return streamName;
    }

}

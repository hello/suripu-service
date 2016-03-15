package com.hello.suripu.service.configuration;


import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;

public class AWSClientConfiguration extends Configuration {
    private static final Integer DEFAULT_MAX_CONNECTIONS = 100;
    private static final Integer DEFAULT_CONNECTION_TIMEOUT = 100;
    private static final Integer DEFAULT_REQUEST_TIMEOUT = 200;
    private static final Integer DEFAULT_CONNECTION_MAX_IDLE_MILLIS = 60 * 1000;
    private static final Integer DEFAULT_MAX_ERROR_RETRY = 1;

    @JsonProperty("max_connections")
    private Integer maxConnections = DEFAULT_MAX_CONNECTIONS;

    @JsonProperty("connection_timeout")
    private Integer connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    @JsonProperty("request_timeout")
    private Integer requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    
    @JsonProperty("connection_max_idle_millis")
    private Integer connectionMaxIdleMillis = DEFAULT_CONNECTION_MAX_IDLE_MILLIS;

    @JsonProperty("max_error_retry")
    private Integer maxErrorRetry = DEFAULT_MAX_ERROR_RETRY;

    public Integer getMaxConnections() {
        return this.maxConnections;
    }
    public Integer getConnectionTimeout() {
        return this.connectionTimeout;
    }
    public Integer getRequestTimeout() {
        return this.requestTimeout;
    }
    public Integer getConnectionMaxIdleMillis() {
        return this.connectionMaxIdleMillis;
    }
    public Integer getMaxErrorRetry() {
        return this.maxErrorRetry;
    }

}

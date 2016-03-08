package com.hello.suripu.service.configuration;


import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import io.dropwizard.Configuration;

public class AWSClientConfiguration extends Configuration {
    private static final String DEFAULT_METRIC_NAMESPACE = "Generic_Namespace";
    private static final Integer DEFAULT_MAX_CONNECTIONS = 100;
    private static final Integer DEFAULT_CONNECTION_TIMEOUT = 100;
    private static final Integer DEFAULT_CONNECTION_MAX_IDLE_MILLIS = 60 * 1000;
    private static final Integer DEFAULT_MAX_ERROR_RETRY = 1;

    @JsonProperty("metric_namespace")
    private String metricNamespace = DEFAULT_METRIC_NAMESPACE;

    @JsonProperty("max_connections")
    private Integer maxConnections = DEFAULT_MAX_CONNECTIONS;

    @JsonProperty("connection_timeout")
    private Integer connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    
    @JsonProperty("connection_max_idle_millis")
    private Integer connectionMaxIdleMillis = DEFAULT_CONNECTION_MAX_IDLE_MILLIS;

    @JsonProperty("max_error_retry")
    private Integer maxErrorRetry = DEFAULT_MAX_ERROR_RETRY;

    public String getMetricNamespace() {
        return this.metricNamespace;
    }
    public Integer getMaxConnections() {
        return this.maxConnections;
    }
    public Integer getConnectionTimeout() {
        return this.connectionTimeout;
    }
    public Integer getConnectionMaxIdleMillis() {
        return this.connectionMaxIdleMillis;
    }
    public Integer getMaxErrorRetry() {
        return this.maxErrorRetry;
    }

}

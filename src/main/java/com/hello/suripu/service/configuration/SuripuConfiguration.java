package com.hello.suripu.service.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.GraphiteConfiguration;
import com.hello.suripu.coredropwizard.configuration.KinesisConfiguration;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class SuripuConfiguration extends Configuration {

    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();

    public DataSourceFactory getCommonDB() {
        return commonDB;
    }

    @Valid
    @NotNull
    @JsonProperty("metrics_enabled")
    private Boolean metricsEnabled;

    public Boolean getMetricsEnabled() {
        return metricsEnabled;
    }

    @Valid
    @JsonProperty("debug")
    private Boolean debug = Boolean.FALSE;

    public Boolean getDebug() {
        return debug;
    }

    @Valid
    @NotNull
    @JsonProperty("graphite")
    private GraphiteConfiguration graphite;

    public GraphiteConfiguration getGraphite() {
        return graphite;
    }


    @Valid
    @NotNull
    @JsonProperty("audio_bucket_name")
    private String audioBucketName;

    public String getAudioBucketName() {
        return audioBucketName;
    }

    @Valid
    @NotNull
    @JsonProperty("kinesis")
    private KinesisConfiguration kinesisConfiguration;

    public KinesisConfiguration getKinesisConfiguration() {
        return kinesisConfiguration;
    }

    @JsonProperty("sense_upload_configuration")
    private SenseUploadConfiguration senseUploadConfiguration;
    public SenseUploadConfiguration getSenseUploadConfiguration() { return this.senseUploadConfiguration; }

    @JsonProperty("ota_configuration")
    private OTAConfiguration otaConfiguration;
    public OTAConfiguration getOTAConfiguration() { return this.otaConfiguration; }

    @JsonProperty("aws_access_key_s3")
    private String awsAccessKeyS3;
    public String getAwsAccessKeyS3() {
        return awsAccessKeyS3;
    }

    @JsonProperty("aws_access_secret_s3")
    private String awsAccessSecretS3;
    public String getAwsAccessSecretS3() {
        return awsAccessSecretS3;
    }

    @Valid
    @NotNull
    @Min(60)
    @JsonProperty("ring_duration_sec")
    private Integer ringDuration;
    public Integer getRingDuration(){
        return this.ringDuration;
    }

    @JsonProperty("calibration_cache_duration_seconds")
    private Integer calibrationCacheDurationSeconds = 300;
    public Integer calibrationCacheDurationSeconds() {
        return calibrationCacheDurationSeconds;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;

    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }

    @JsonProperty("aws_metric_namespace")
    private String awsMetricNamespace;
    public String getAwsMetricNamespace() {
        return awsMetricNamespace;
    }

    @JsonProperty("dynamo_client_configuration")
    private AWSClientConfiguration dynamoClientConfiguration;
    public AWSClientConfiguration getDynamoClientConfiguration() { return dynamoClientConfiguration; }

    @JsonProperty("kinesis_client_configuration")
    private AWSClientConfiguration kinesisClientConfiguration;
    public AWSClientConfiguration getKinesisClientConfiguration() { return kinesisClientConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("audio_features_firehose")
    private FirehoseConfiguration audioFeaturesFirehoseConfiguration;
    public FirehoseConfiguration getAudioFirehoseConfiguration() {
        return audioFeaturesFirehoseConfiguration;
    }
}

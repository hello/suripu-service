package com.hello.suripu.service;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.metrics.AwsSdkMetrics;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClient;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.collect.ImmutableMap;

import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.hello.dropwizard.mikkusu.helpers.JacksonProtobufProvider;
import com.hello.dropwizard.mikkusu.resources.PingResource;
import com.hello.dropwizard.mikkusu.resources.VersionResource;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.db.SenseStateDynamoDB;
import com.hello.suripu.core.flipper.DynamoDBAdapter;
import com.hello.suripu.core.oauth.stores.PersistentApplicationStore;
import com.hello.suripu.coredw8.filters.SlowRequestsFilter;

import com.hello.suripu.coredw8.db.AccessTokenDAO;
import com.hello.suripu.coredw8.health.DynamoDbHealthCheck;
import com.hello.suripu.coredw8.health.KinesisHealthCheck;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.configuration.QueueName;

import com.hello.suripu.core.db.ApplicationsDAO;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.CalibrationDynamoDB;
import com.hello.suripu.core.db.DeviceDAO;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.FirmwareUpgradePathDAO;
import com.hello.suripu.core.db.FirmwareVersionMappingDAO;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.db.MergedUserInfoDynamoDB;
import com.hello.suripu.core.db.OTAHistoryDAODynamoDB;
import com.hello.suripu.core.db.ResponseCommandsDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.TeamStore;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.db.util.PostgresIntegerArrayArgumentFactory;
import com.hello.suripu.core.firmware.FirmwareUpdateStore;
import com.hello.suripu.core.flipper.GroupFlipper;

import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;

import com.hello.suripu.coredw8.managers.DynamoDBClientManaged;
import com.hello.suripu.coredw8.managers.KinesisClientManaged;
import com.hello.suripu.coredw8.oauth.AccessToken;
import com.hello.suripu.coredw8.oauth.AuthDynamicFeature;
import com.hello.suripu.coredw8.oauth.AuthValueFactoryProvider;
import com.hello.suripu.coredw8.oauth.OAuthAuthenticator;
import com.hello.suripu.coredw8.oauth.OAuthAuthorizer;
import com.hello.suripu.coredw8.oauth.OAuthCredentialAuthFilter;
import com.hello.suripu.coredw8.oauth.ScopesAllowedDynamicFeature;
import com.hello.suripu.coredw8.oauth.stores.PersistentAccessTokenStore;
import com.hello.suripu.coredw8.util.CustomJSONExceptionMapper;
import com.hello.suripu.service.cli.CreateDynamoDBTables;
import com.hello.suripu.service.configuration.AWSClientConfiguration;
import com.hello.suripu.service.configuration.SuripuConfiguration;
import com.hello.suripu.service.modules.RolloutModule;
import com.hello.suripu.service.resources.AudioResource;
import com.hello.suripu.service.resources.CheckResource;
import com.hello.suripu.service.resources.LogsResource;
import com.hello.suripu.service.resources.ProvisionResource;
import com.hello.suripu.service.resources.ReceiveResource;
import com.hello.suripu.service.resources.RegisterResource;
import com.librato.rollout.RolloutClient;

import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;

import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.jdbi.bundles.DBIExceptionsBundle;
import io.dropwizard.Application;
import io.dropwizard.server.AbstractServerFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class SuripuService extends Application<SuripuConfiguration> {

    private final static Logger LOGGER = LoggerFactory.getLogger(SuripuService.class);

    public static void main(final String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DateTimeZone.setDefault(DateTimeZone.UTC);
        new SuripuService().run(args);
    }

    @Override
    public void initialize(Bootstrap<SuripuConfiguration> bootstrap) {
        bootstrap.addBundle(new DBIExceptionsBundle());
        bootstrap.addCommand(new CreateDynamoDBTables());
    }

    @Override
    public void run(final SuripuConfiguration configuration, Environment environment) throws Exception {
        environment.jersey().register(new JacksonProtobufProvider());

        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, configuration.getCommonDB(), "postgresql");

        commonDB.registerArgumentFactory(new JodaArgumentFactory());
        commonDB.registerContainerFactory(new OptionalContainerFactory());
        commonDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());

        final DeviceDAO deviceDAO = commonDB.onDemand(DeviceDAO.class);

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        // Checks Environment first and then instance profile.
        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        if(configuration.getMetricsEnabled()) {
            AwsSdkMetrics.enableDefaultMetrics();
            AwsSdkMetrics.setCredentialProvider(awsCredentialsProvider);
            AwsSdkMetrics.setMetricNameSpace(configuration.getAwsMetricNamespace());
        }

        final AWSClientConfiguration dynamoConfiguration = configuration.getDynamoClientConfiguration();
        final ClientConfiguration DynamoClientConfig = new ClientConfiguration()
            .withConnectionTimeout(dynamoConfiguration.getConnectionTimeout())
            .withConnectionMaxIdleMillis(dynamoConfiguration.getConnectionMaxIdleMillis())
            .withMaxErrorRetry(dynamoConfiguration.getMaxErrorRetry())
            .withMaxConnections(dynamoConfiguration.getMaxConnections())
            .withRequestTimeout(dynamoConfiguration.getRequestTimeout());

        final AmazonDynamoDBClientFactory dynamoDBFactory = AmazonDynamoDBClientFactory.create(awsCredentialsProvider, DynamoClientConfig, configuration.dynamoDBConfiguration());

        final AmazonDynamoDB senseKeyStoreDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.SENSE_KEY_STORE);

        final AmazonS3Client s3Client = new AmazonS3Client(awsCredentialsProvider); //using default client config values for S3
        final String bucketName = configuration.getAudioBucketName();

        final AmazonDynamoDB mergedInfoDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.ALARM_INFO);
        final MergedUserInfoDynamoDB mergedUserInfoDynamoDB = new MergedUserInfoDynamoDB(mergedInfoDynamoDBClient, tableNames.get(DynamoDBTableName.ALARM_INFO));


        final AmazonDynamoDB ringTimeHistoryDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.RING_TIME_HISTORY);
        final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB = new RingTimeHistoryDAODynamoDB(ringTimeHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.RING_TIME_HISTORY));

        final AmazonDynamoDB otaHistoryDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.OTA_HISTORY);
        final OTAHistoryDAODynamoDB otaHistoryDAODynamoDB = new OTAHistoryDAODynamoDB(otaHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.OTA_HISTORY));

        final AmazonDynamoDB respCommandsDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.SYNC_RESPONSE_COMMANDS);
        final ResponseCommandsDAODynamoDB respCommandsDAODynamoDB = new ResponseCommandsDAODynamoDB(respCommandsDynamoDBClient, tableNames.get(DynamoDBTableName.SYNC_RESPONSE_COMMANDS));

        final AmazonDynamoDB fwVersionMapping = dynamoDBFactory.getForTable(DynamoDBTableName.FIRMWARE_VERSIONS);
        final FirmwareVersionMappingDAO firmwareVersionMappingDAO = new FirmwareVersionMappingDAO(fwVersionMapping, tableNames.get(DynamoDBTableName.FIRMWARE_VERSIONS));

        final AmazonDynamoDB fwUpgradePathDynamoDB = dynamoDBFactory.getForTable(DynamoDBTableName.FIRMWARE_UPGRADE_PATH);
        final FirmwareUpgradePathDAO firmwareUpgradePathDAO = new FirmwareUpgradePathDAO(fwUpgradePathDynamoDB, tableNames.get(DynamoDBTableName.FIRMWARE_UPGRADE_PATH));

        // This is used to sign S3 urls with a shorter signature
        final AWSCredentials s3credentials = new AWSCredentials() {
            @Override
            public String getAWSAccessKeyId() {
                return configuration.getAwsAccessKeyS3();
            }

            @Override
            public String getAWSSecretKey() {
                return configuration.getAwsAccessSecretS3();
            }
        };

        final AmazonS3 amazonS3UrlSigner = new AmazonS3Client(s3credentials);

        final AWSClientConfiguration kinesisConfiguration = configuration.getKinesisClientConfiguration();
        final ClientConfiguration KinesisClientConfig = new ClientConfiguration()
            .withConnectionTimeout(kinesisConfiguration.getConnectionTimeout())
            .withConnectionMaxIdleMillis(kinesisConfiguration.getConnectionMaxIdleMillis())
            .withMaxErrorRetry(kinesisConfiguration.getMaxErrorRetry())
            .withMaxConnections(kinesisConfiguration.getMaxConnections());

        final AmazonKinesisAsyncClient kinesisClient = new AmazonKinesisAsyncClient(awsCredentialsProvider, KinesisClientConfig);
        kinesisClient.setEndpoint(configuration.getKinesisConfiguration().getEndpoint());

        final KinesisLoggerFactory kinesisLoggerFactory = new KinesisLoggerFactory(
                kinesisClient,
                configuration.getKinesisConfiguration().getStreams()
        );


        final KeyStore senseKeyStore = new KeyStoreDynamoDB(
                senseKeyStoreDynamoDBClient,
                tableNames.get(DynamoDBTableName.SENSE_KEY_STORE),
                "1234567891234567".getBytes(), // TODO: REMOVE THIS WHEN WE ARE NOT SUPPOSED TO HAVE A DEFAULT KEY
                120 // 2 minutes for cache
        );

        if(configuration.getMetricsEnabled()) {
          final String graphiteHostName = configuration.getGraphite().getHost();
          final String apiKey = configuration.getGraphite().getApiKey();
          final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

          final String env = (configuration.getDebug()) ? "dev" : "prod";
          final String prefix = String.format("%s.%s.suripu-service", apiKey, env);

          final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));

          final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
              .prefixedWith(prefix)
              .convertRatesTo(TimeUnit.SECONDS)
              .convertDurationsTo(TimeUnit.MILLISECONDS)
              .filter(MetricFilter.ALL)
              .build(graphite);
          reporter.start(interval, TimeUnit.SECONDS);

          LOGGER.info("Metrics enabled.");
        } else {
          LOGGER.warn("Metrics not enabled.");
        }

        final FirmwareUpdateStore firmwareUpdateStore = FirmwareUpdateStore.create(
                otaHistoryDAODynamoDB,
                s3Client,
                "hello-firmware",
                amazonS3UrlSigner,
                configuration.getOTAConfiguration().getS3CacheExpireMinutes(),
                firmwareVersionMappingDAO,
                firmwareUpgradePathDAO);


        //Doing this programmatically instead of in config files
        AbstractServerFactory sf = (AbstractServerFactory) configuration.getServerFactory();
        // disable all default exception mappers
        sf.setRegisterDefaultExceptionMappers(false);

        environment.jersey().register(new CustomJSONExceptionMapper(configuration.getDebug()));

        final AccessTokenDAO accessTokenDAO = commonDB.onDemand(AccessTokenDAO.class);
        final ApplicationsDAO applicationsDAO = commonDB.onDemand(ApplicationsDAO.class);
        final PersistentApplicationStore applicationStore = new PersistentApplicationStore(applicationsDAO);
        final PersistentAccessTokenStore tokenStore = new PersistentAccessTokenStore(accessTokenDAO, applicationStore);
        final DataLogger activityLogger = kinesisLoggerFactory.get(QueueName.ACTIVITY_STREAM);
        environment.jersey().register(new AuthDynamicFeature(new OAuthCredentialAuthFilter.Builder<AccessToken>()
            .setAuthenticator(new OAuthAuthenticator(tokenStore))
            .setAuthorizer(new OAuthAuthorizer())
            .setRealm("SUPER SECRET STUFF")
            .setPrefix("Bearer")
            .setLogger(activityLogger)
            .buildAuthFilter()));
        environment.jersey().register(ScopesAllowedDynamicFeature.class);
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(AccessToken.class));

        final AmazonDynamoDB teamStoreDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.TEAMS);
        final TeamStore teamStore = new TeamStore(teamStoreDynamoDBClient, tableNames.get(DynamoDBTableName.TEAMS));

        final GroupFlipper groupFlipper = new GroupFlipper(teamStore, 30);

        final String namespace = (configuration.getDebug()) ? "dev" : "prod";
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.FEATURES);
        final FeatureStore featureStore = new FeatureStore(featuresDynamoDBClient, tableNames.get(DynamoDBTableName.FEATURES), namespace);

        final AmazonDynamoDB senseStateDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.SENSE_STATE);
        final SenseStateDynamoDB senseStateDynamoDB = new SenseStateDynamoDB(senseStateDynamoDBClient, tableNames.get(DynamoDBTableName.SENSE_STATE));


        final RolloutModule module = new RolloutModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(module);

        environment.jersey().register(new AbstractBinder() {
          @Override
          protected void configure() {
            bind(new RolloutClient(new DynamoDBAdapter(featureStore, 30))).to(RolloutClient.class);
          }
        });

        final AmazonDynamoDB calibrationDynamoDBClient = dynamoDBFactory.getForTable(DynamoDBTableName.CALIBRATION);

        // 300 sec = 5 minutes, which should maximize cache hitrate
        // TODO: add cache hitrate to metrics
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.createWithCacheConfig(calibrationDynamoDBClient, tableNames.get(DynamoDBTableName.CALIBRATION), 300);

        final ReceiveResource receiveResource = new ReceiveResource(
                senseKeyStore,
                kinesisLoggerFactory,
                mergedUserInfoDynamoDB,
                ringTimeHistoryDAODynamoDB,
                configuration.getDebug(),
                firmwareUpdateStore,
                groupFlipper,
                configuration.getSenseUploadConfiguration(),
                configuration.getOTAConfiguration(),
                respCommandsDAODynamoDB,
                configuration.getRingDuration(),
                calibrationDAO,
                environment.metrics(),
                senseStateDynamoDB
        );


        environment.jersey().register(receiveResource);
        environment.jersey().register(new RegisterResource(deviceDAO,
            tokenStore,
            kinesisLoggerFactory,
            senseKeyStore,
            mergedUserInfoDynamoDB,
            groupFlipper,
            configuration.getDebug()));


        final FilterRegistration.Dynamic builder = environment.servlets().addFilter("slowRequestsFilter", SlowRequestsFilter.class);
        builder.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

        final DataLogger senseLogs = kinesisLoggerFactory.get(QueueName.LOGS);
        final LogsResource logsResource = new LogsResource(
                !configuration.getDebug(),
                senseKeyStore,
                senseLogs
        );

        environment.jersey().register(new CheckResource(senseKeyStore));
        environment.jersey().register(logsResource);

        environment.jersey().register(new PingResource());
        environment.jersey().register(new VersionResource());

        final DataLogger audioDataLogger = kinesisLoggerFactory.get(QueueName.AUDIO_FEATURES);
        final DataLogger audioMetaDataLogger = kinesisLoggerFactory.get(QueueName.ENCODE_AUDIO);
        environment.jersey().register(
            new AudioResource(
                s3Client,
                bucketName,
                audioDataLogger,
                configuration.getDebug(),
                audioMetaDataLogger,
                senseKeyStore));

        environment.jersey().register(new ProvisionResource(senseKeyStore, groupFlipper));

        // Manage the lifecycle of our clients
        environment.lifecycle().manage(new DynamoDBClientManaged(senseKeyStoreDynamoDBClient));
        environment.lifecycle().manage(new DynamoDBClientManaged(teamStoreDynamoDBClient));
        environment.lifecycle().manage(new DynamoDBClientManaged(featuresDynamoDBClient));
        environment.lifecycle().manage(new DynamoDBClientManaged(senseKeyStoreDynamoDBClient));
        environment.lifecycle().manage(new KinesisClientManaged(kinesisClient));

        // Make sure we can connect
        environment.healthChecks().register("keystore-healthcheck", new DynamoDbHealthCheck(senseKeyStoreDynamoDBClient));
        environment.healthChecks().register("teamstore-healthcheck", new DynamoDbHealthCheck(teamStoreDynamoDBClient));
        environment.healthChecks().register("features-healthcheck", new DynamoDbHealthCheck(featuresDynamoDBClient));
        environment.healthChecks().register("kinesis-healthcheck", new KinesisHealthCheck(kinesisClient));
    }


}

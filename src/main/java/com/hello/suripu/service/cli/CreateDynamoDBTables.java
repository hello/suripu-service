package com.hello.suripu.service.cli;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.firmware.FirmwareFile;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.firmware.db.OTAFileSettingsDynamoDB;
import com.hello.suripu.coredw8.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.service.configuration.SuripuConfiguration;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

import java.util.Map;

public class CreateDynamoDBTables extends ConfiguredCommand<SuripuConfiguration> {

    public CreateDynamoDBTables() {
        super("create_dynamodb_tables_service", "Create service specific dynamoDB tables");
    }

    @Override
    protected void run(Bootstrap<SuripuConfiguration> bootstrap, Namespace namespace, SuripuConfiguration configuration) throws Exception {

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        createRingTimeHistoryTable(configuration, awsCredentialsProvider);
        createOtaFileSettingsTable(configuration, awsCredentialsProvider);
    }


    private void createOtaFileSettingsTable(final SuripuConfiguration configuration, final AWSCredentialsProvider awsCredentialsProvider) throws InterruptedException {
        final AmazonDynamoDBClient client = new AmazonDynamoDBClient(awsCredentialsProvider);
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final ImmutableMap<DynamoDBTableName, String> endpoints = configuration.dynamoDBConfiguration().endpoints();

        final String tableName = tableNames.get(DynamoDBTableName.OTA_FILE_SETTINGS);
        final String endpoint = endpoints.get(DynamoDBTableName.OTA_FILE_SETTINGS);

        client.setEndpoint(endpoint);
        try {
            client.describeTable(tableName);
            System.out.println(String.format("%s already exists.", tableName));
        } catch (AmazonServiceException exception) {
            final TableDescription description = OTAFileSettingsDynamoDB.createTable(client, tableName);
            System.out.println(tableName + ": " + description.getTableStatus());
        }

        // TODO: remove this
        // Adds a few files to db for hardware version
        final OTAFileSettingsDynamoDB ddb = OTAFileSettingsDynamoDB.create(client, tableName, new ObjectMapper());

        final FirmwareFile kitsune = FirmwareFile.create(true, false, true, "mcuimgx.bin", "/sys/", "mcuimgx.bin", "/");
        final FirmwareFile top = FirmwareFile.create(true, false, false, "top.bin", "/top/", "update.bin", "/");
        final FirmwareFile sp = FirmwareFile.create(true, false, false, "servicepack.ucf", "/sys/", "servicepack.ucf", "/");

        final Map<String, FirmwareFile> map = Maps.newHashMap();
        map.put("kitsune.bin", kitsune);
        map.put("top.bin", top);
        map.put("servicepack.ucf", sp);
        ddb.put(HardwareVersion.SENSE_ONE, map);


    }

    private void createRingTimeHistoryTable(final SuripuConfiguration configuration, final AWSCredentialsProvider awsCredentialsProvider){
        final NewDynamoDBConfiguration config = configuration.dynamoDBConfiguration();
        final AmazonDynamoDBClient client = new AmazonDynamoDBClient(awsCredentialsProvider);
        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();
        final ImmutableMap<DynamoDBTableName, String> endpoints = configuration.dynamoDBConfiguration().endpoints();

        final String tableName = tableNames.get(DynamoDBTableName.RING_TIME_HISTORY);
        final String endpoint = endpoints.get(DynamoDBTableName.RING_TIME_HISTORY);

        client.setEndpoint(endpoint);
        try {
            client.describeTable(tableName);
            System.out.println(String.format("%s already exists.", tableName));
        } catch (AmazonServiceException exception) {
            final CreateTableResult result = RingTimeHistoryDAODynamoDB.createTable(tableName, client);
            final TableDescription description = result.getTableDescription();
            System.out.println(tableName + ": " + description.getTableStatus());
        }
    }

}

package com.hello.suripu.service.cli;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.hello.suripu.core.db.PublicKeyStore;
import com.hello.suripu.core.db.PublicKeyStoreDynamoDB;
import com.hello.suripu.core.db.RingTimeDAODynamoDB;
import com.hello.suripu.service.configuration.SuripuConfiguration;
import com.yammer.dropwizard.cli.ConfiguredCommand;
import com.yammer.dropwizard.config.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

public class CreateKeyStoreDynamoDBTable extends ConfiguredCommand<SuripuConfiguration> {

    public CreateKeyStoreDynamoDBTable() {
        super("create_keystore_dynamodb_table", "Create dynamoDB keystore table");
    }

    @Override
    protected void run(Bootstrap<SuripuConfiguration> bootstrap, Namespace namespace, SuripuConfiguration configuration) throws Exception {

        final AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        final AmazonDynamoDBClient client = new AmazonDynamoDBClient(awsCredentialsProvider);

        client.setEndpoint(configuration.getRingTimeDBConfiguration().getEndpoint());
        final String tableName = configuration.getRingTimeDBConfiguration().getTableName();

        final CreateTableResult result = PublicKeyStoreDynamoDB.createTable(tableName, client);
        final TableDescription description = result.getTableDescription();
        System.out.println(description.getTableStatus());
    }
}
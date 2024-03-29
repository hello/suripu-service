package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.ble.SenseCommandProtos;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.KeyStoreDynamoDB;
import com.hello.suripu.core.oauth.ClientCredentials;
import com.hello.suripu.core.oauth.ClientDetails;
import com.hello.suripu.core.oauth.MissingRequiredScopeException;
import com.hello.suripu.core.oauth.OAuthScope;
import com.hello.suripu.core.oauth.stores.OAuthTokenStore;
import com.hello.suripu.core.swap.Swapper;
import com.hello.suripu.core.util.HelloHttpHeader;
import com.hello.suripu.core.util.PairAction;
import com.hello.suripu.coredropwizard.oauth.AccessToken;
import com.hello.suripu.service.SignedMessage;
import com.hello.suripu.service.pairing.NoOpRegistrationLogger;
import com.hello.suripu.service.pairing.PairingAttempt;
import com.hello.suripu.service.pairing.PairingManager;
import com.hello.suripu.service.pairing.PairingResult;
import com.hello.suripu.service.utils.RegistrationLogger;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

/**
 * Created by pangwu on 5/5/15.
 */
public class RegisterResourceIntegrationTest extends ResourceTest {

    private static final String SENSE_ID = "test sense";
    private static final String ACCESS_TOKEN = "test access token";
    private static final byte[] KEY = "1234567891234567".getBytes();
    private RegisterResource registerResource;
    private PairingManager pairingManager;
    private RegistrationLogger registrationLogger;

    private Swapper swapper;

    private Optional<AccessToken> stubGetClientDetailsByToken(final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore) {
        try {
            return tokenStore.getTokenByClientCredentials(any(ClientCredentials.class), any(DateTime.class));
        } catch (MissingRequiredScopeException e) {
            return Optional.absent();
        }
    }

    private void stubGetClientDetailsByTokenThatReturnsNoAccessToken(final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore){
        when(stubGetClientDetailsByToken(tokenStore)).thenReturn(Optional.<AccessToken>absent());
    }

    private void stubGetClientDetails(final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore,
                                      final Optional<AccessToken> returnValue){
        when(stubGetClientDetailsByToken(tokenStore)).thenReturn(returnValue);
    }

    private AccessToken getAccessToken(){
        return new AccessToken(UUID.randomUUID(), UUID.randomUUID(), 0L, 0L, DateTime.now(), 1L, 1L, new OAuthScope[]{ OAuthScope.AUTH });
    }

    private byte[] generateInvalidEncryptedMessage(){
        final byte[] raw = new byte[10];
        return raw;
    }

    private byte[] generateValidProtobufWithSignature(final byte[] key){
        final SenseCommandProtos.MorpheusCommand command = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setAccountId(ACCESS_TOKEN)
                .setVersion(1)
                .setDeviceId(SENSE_ID)
                .build();

        final byte[] body  = command.toByteArray();
        final Optional<byte[]> signedOptional = SignedMessage.sign(body, key);
        assertThat(signedOptional.isPresent(), is(true));
        final byte[] signed = signedOptional.get();
        final byte[] iv = Arrays.copyOfRange(signed, 0, 16);
        final byte[] sig = Arrays.copyOfRange(signed, 16, 16 + 32);
        final byte[] message = new byte[signed.length];
        copyTo(message, body, 0, body.length);
        copyTo(message, iv, body.length, body.length + iv.length);
        copyTo(message, sig, body.length + iv.length, message.length);
        return message;

    }

    private byte[] generateValidProtobufWithInvalidSignature(final byte[] key){
        final SenseCommandProtos.MorpheusCommand command = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setAccountId(ACCESS_TOKEN)
                .setVersion(1)
                .setDeviceId(SENSE_ID)
                .build();

        final byte[] body  = command.toByteArray();
        final Optional<byte[]> signedOptional = SignedMessage.sign(body, key);
        assertThat(signedOptional.isPresent(), is(true));
        final byte[] signed = signedOptional.get();
        final byte[] iv = Arrays.copyOfRange(signed, 0, 16);
        final byte[] message = new byte[signed.length];
        copyTo(message, body, 0, body.length);
        copyTo(message, iv, body.length, body.length + iv.length);
        copyTo(message, new byte[32], body.length + iv.length, message.length);
        return message;

    }

    private byte[] generateInvalidProtobuf(final byte[] key){
        final SenseCommandProtos.MorpheusCommand command = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setAccountId(ACCESS_TOKEN)
                .setVersion(1)
                .setDeviceId(SENSE_ID)
                .build();

        final byte[] body  = command.toByteArray();
        final Optional<byte[]> signedOptional = SignedMessage.sign(body, key);
        assertThat(signedOptional.isPresent(), is(true));
        return signedOptional.get();

    }

    private void copyTo(final byte[] dest, final byte[] src, final int start, final int end){
        for(int i = start; i < end; i++){
            dest[i] = src[i-start];
        }

    }

    @Before
    public void setUp(){
        super.setUp();

        BaseResourceTestHelper.kinesisLoggerFactoryStubGet(this.kinesisLoggerFactory, QueueName.LOGS, this.dataLogger);
        BaseResourceTestHelper.kinesisLoggerFactoryStubGet(this.kinesisLoggerFactory, QueueName.REGISTRATIONS, this.dataLogger);

        pairingManager = mock(PairingManager.class);
        registrationLogger = mock(RegistrationLogger.class);
        swapper = mock(Swapper.class);

        when(pairingManager.withLogger(any(RegistrationLogger.class))).thenReturn(pairingManager);
        when(pairingManager.logger()).thenReturn(new NoOpRegistrationLogger());
        when(keyStore.get(any(String.class))).thenReturn(Optional.of(KeyStoreDynamoDB.DEFAULT_AES_KEY));
        
        final RegisterResource registerResource = new RegisterResource(deviceDAO,
                oAuthTokenStore,
                kinesisLoggerFactory,
                keyStore,
                groupFlipper,
                pairingManager);
        registerResource.request = httpServletRequest;
        this.registerResource = spy(registerResource);  // the registerResource is a real object, we need to spy it.

        BaseResourceTestHelper.stubGetHeader(this.registerResource.request, "X-Forwarded-For", "127.0.0.1");
    }

    @Test(expected = WebApplicationException.class)
    public void testPairingCannotDecryptMessage(){
        this.registerResource.pair(SENSE_ID,
                generateInvalidEncryptedMessage(),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1");
        verify(this.registerResource).throwPlainTextError(Response.Status.BAD_REQUEST, "");

    }

    @Test(expected = WebApplicationException.class)
    public void testPairingCannotParseProtobuf(){
        this.registerResource.pair(SENSE_ID,
                generateInvalidProtobuf(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS,"127.0.0.1");
        verify(this.registerResource).throwPlainTextError(Response.Status.BAD_REQUEST, "");
    }


    /*
    * simulate scenario that no account can be found with the token provided
     */
    @Test
    public void testPairingCannotFindToken(){
        // simulate scenario that no account can be found with the token provided
        stubGetClientDetailsByTokenThatReturnsNoAccessToken(this.oAuthTokenStore);

        final SenseCommandProtos.MorpheusCommand.Builder builder = this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1");
        assertThat(builder.getType(), is(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_ERROR));
        assertThat(builder.getError(), is(SenseCommandProtos.ErrorType.INTERNAL_OPERATION_FAILED));
    }


    @Test(expected = WebApplicationException.class)
    public void testPairingInvalidSignature(){
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.of(KEY));

        this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithInvalidSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1");
        verify(this.registerResource).throwPlainTextError(Response.Status.UNAUTHORIZED, "invalid signature");

    }

    @Test(expected = WebApplicationException.class)
    public void testPairingNoKey(){
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.<byte[]>absent());

        this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithInvalidSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1");
        verify(this.registerResource).throwPlainTextError(Response.Status.UNAUTHORIZED, "no key");
        verify(this.deviceDAO, times(0)).registerSense(1L, SENSE_ID);
    }

    /*
    * Happy pass for pairing
     */
    @Test
    public void testPairSense(){
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.of(KEY));
        final SenseCommandProtos.MorpheusCommand.Builder builder = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setVersion(0);
        when(pairingManager.pairSense(any(PairingAttempt.class))).thenReturn(new PairingResult(builder, registrationLogger));
        final SenseCommandProtos.MorpheusCommand command = this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1").build();
        assertEquals(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE, command.getType());
    }

    /*
    * Happy pass for testing the endpoint function
     */
    @Test
    public void testRegisterSense() throws InvalidProtocolBufferException {
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.of(KEY));
        final SenseCommandProtos.MorpheusCommand.Builder builder = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setVersion(0);
        when(pairingManager.pairSense(any(PairingAttempt.class))).thenReturn(new PairingResult(builder, registrationLogger));        BaseResourceTestHelper.stubGetHeader(this.httpServletRequest, HelloHttpHeader.SENSE_ID, SENSE_ID);

        final byte[] data = this.registerResource.registerMorpheus(generateValidProtobufWithSignature(KEY));
        final byte[] protobufBytes = Arrays.copyOfRange(data, 16 + 32, data.length);

        final SenseCommandProtos.MorpheusCommand command = SenseCommandProtos.MorpheusCommand.parseFrom(protobufBytes);
        assertFalse("should not have accountId", command.hasAccountId());
        assertEquals(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE, command.getType());
        
    }


    /*
    * Test when the sam account tries to pair twice
     */
    @Test
    public void testPairAlreadyPairedSense(){
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.of(KEY));

        final SenseCommandProtos.MorpheusCommand.Builder builder = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setVersion(0);
        
        when(pairingManager.pairSense(any(PairingAttempt.class))).thenReturn(new PairingResult(builder, registrationLogger));
        final SenseCommandProtos.MorpheusCommand command = this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1").build();
        verify(this.deviceDAO, times(0)).registerSense(1L, SENSE_ID);
        assertThat(command.getType(), is(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE));
    }

    /*
    * Test one account tries to pair to two different Senses.
     */
    @Test
    public void testPairAlreadyPairedSenseToDifferentAccount(){
        stubGetClientDetails(this.oAuthTokenStore, Optional.of(getAccessToken()));
        BaseResourceTestHelper.stubKeyFromKeyStore(this.keyStore, SENSE_ID, Optional.of(KEY));
        final SenseCommandProtos.MorpheusCommand.Builder builder = SenseCommandProtos.MorpheusCommand.newBuilder()
                .setType(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE)
                .setVersion(0);
        when(pairingManager.pairSense(any(PairingAttempt.class))).thenReturn(new PairingResult(builder, registrationLogger));

        final SenseCommandProtos.MorpheusCommand command = this.registerResource.pair(SENSE_ID,
                generateValidProtobufWithSignature(KEY),
                this.keyStore,
                PairAction.PAIR_MORPHEUS, "127.0.0.1").build();
        assertEquals(SenseCommandProtos.MorpheusCommand.CommandType.MORPHEUS_COMMAND_PAIR_SENSE, command.getType());
        assertFalse(command.hasError());

    }
}

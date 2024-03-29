package com.hello.suripu.service.resources;

import com.google.common.base.Optional;
import com.hello.suripu.api.input.FileSync;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.configuration.QueueName;
import com.hello.suripu.core.db.KeyStore;
import com.hello.suripu.core.firmware.HardwareVersion;
import com.hello.suripu.core.logging.DataLogger;
import com.hello.suripu.core.logging.KinesisLoggerFactory;
import com.hello.suripu.core.oauth.ClientCredentials;
import com.hello.suripu.core.oauth.ClientDetails;
import com.hello.suripu.core.oauth.MissingRequiredScopeException;
import com.hello.suripu.core.oauth.OAuthScope;
import com.hello.suripu.core.oauth.stores.OAuthTokenStore;
import com.hello.suripu.coredropwizard.oauth.AccessToken;
import com.hello.suripu.service.file_sync.FileSynchronizer;
import org.joda.time.DateTime;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

/**
 * Created by pangwu on 5/5/15.
 */
public class BaseResourceTestHelper {
    public static void stubKeyFromKeyStore(final KeyStore keyStore, final String deviceId, final Optional<byte[]> returnValue){
        when(keyStore.get(deviceId)).thenReturn(returnValue);
    }

    public static void stubGetHeader(final HttpServletRequest httpServletRequest, final String header, final String returnValue){
        when(httpServletRequest.getHeader(header)).thenReturn(returnValue);
    }

    public static void stubInject(final ObjectGraphRoot objectGraphRoot, final Class targetClass){
        when(objectGraphRoot.inject(targetClass)).thenReturn(Mockito.<Class>mock(targetClass));
    }

    public static void kinesisLoggerFactoryStubGet(final KinesisLoggerFactory kinesisLoggerFactory,
                                                   final QueueName queueName, final DataLogger returnValueForGet){
        when(kinesisLoggerFactory.get(queueName)).thenReturn(returnValueForGet);
    }

    public static void stubGetClientDetails(final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore,
                                      final Optional<AccessToken> returnValue){
        when(stubGetClientDetailsByToken(tokenStore)).thenReturn(returnValue);
    }

    public static AccessToken getAccessToken(){
        return new AccessToken(UUID.randomUUID(), UUID.randomUUID(), 0L, 0L, DateTime.now(), 1L, 1L, new OAuthScope[]{ OAuthScope.AUTH });
    }

    public static Optional<AccessToken> stubGetClientDetailsByToken(final OAuthTokenStore<AccessToken, ClientDetails, ClientCredentials> tokenStore) {
        try {
            return tokenStore.getTokenByClientCredentials(any(ClientCredentials.class), any(DateTime.class));
        } catch (MissingRequiredScopeException e) {
            return Optional.absent();
        }
    }

    public static void stubSynchronizeFileManifest(final FileSynchronizer synchronizer, final FileSync.FileManifest newManifest) {
        when(synchronizer.synchronizeFileManifest(Mockito.anyString(), Mockito.any(FileSync.FileManifest.class), Mockito.anyBoolean(), Mockito.any(HardwareVersion.class))).thenReturn(newManifest);
    }
}

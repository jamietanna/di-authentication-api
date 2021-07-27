package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.auth.PrivateKeyJWT;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.entity.ClientRegistry;
import uk.gov.di.entity.ErrorResponse;
import uk.gov.di.services.AuthenticationService;
import uk.gov.di.services.ClientService;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.TokenService;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasBody;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

public class TokenHandlerTest {

    private static final String TEST_EMAIL = "joe.bloggs@digital.cabinet-office.gov.uk";
    private static final Subject TEST_SUBJECT = new Subject();
    private static final String CLIENT_ID = "test-id";
    private static final String ENDPOINT_URI = "http://localhost/token";
    private final Context context = mock(Context.class);
    private final SignedJWT signedJWT = mock(SignedJWT.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final ClientService clientService = mock(ClientService.class);
    private TokenHandler handler;

    @BeforeEach
    public void setUp() {
        handler =
                new TokenHandler(
                        clientService, tokenService, authenticationService, configurationService);
    }

    @Test
    public void shouldReturn200IfSuccessfulRequest() throws JOSEException {
        KeyPair keyPair = generateRsaKeyPair();
        PrivateKeyJWT privateKeyJWT = generatePrivateKeyJWT(CLIENT_ID, keyPair.getPrivate());
        ClientRegistry clientRegistry =
                new ClientRegistry()
                        .setClientID(CLIENT_ID)
                        .setClientName("test-client")
                        .setRedirectUrls(singletonList("http://localhost/redirect"))
                        .setScopes(singletonList("openid"))
                        .setContacts(singletonList(TEST_EMAIL))
                        .setPublicKey(
                                Base64.getMimeEncoder()
                                        .encodeToString(keyPair.getPublic().getEncoded()));

        BearerAccessToken accessToken = new BearerAccessToken();
        when(configurationService.getBaseURL()).thenReturn(Optional.of(ENDPOINT_URI));
        when(clientService.getClient(eq(CLIENT_ID))).thenReturn(Optional.of(clientRegistry));
        when(tokenService.validatePrivateKeyJWTSignature(
                        eq(clientRegistry.getPublicKey()),
                        any(PrivateKeyJWT.class),
                        eq(ENDPOINT_URI)))
                .thenReturn(true);
        when(authenticationService.getSubjectFromEmail(eq(TEST_EMAIL))).thenReturn(TEST_SUBJECT);
        when(tokenService.generateAndStoreAccessToken(eq(TEST_SUBJECT))).thenReturn(accessToken);
        when(tokenService.generateIDToken(eq(CLIENT_ID), any(Subject.class))).thenReturn(signedJWT);

        APIGatewayProxyResponseEvent result = generateApiGatewayRequest(privateKeyJWT, CLIENT_ID);

        assertThat(result, hasStatus(200));
        assertTrue(result.getBody().contains(accessToken.getValue()));
    }

    @Test
    public void shouldReturn403IfClientIsNotValid() throws JOSEException, JsonProcessingException {
        String invalidClientID = "invalid-id";
        when(clientService.getClient(eq(invalidClientID))).thenReturn(Optional.empty());
        KeyPair keyPair = generateRsaKeyPair();
        PrivateKeyJWT privateKeyJWT = generatePrivateKeyJWT(invalidClientID, keyPair.getPrivate());

        APIGatewayProxyResponseEvent result =
                generateApiGatewayRequest(privateKeyJWT, invalidClientID);

        assertEquals(403, result.getStatusCode());
        String expectedResponse = new ObjectMapper().writeValueAsString(ErrorResponse.ERROR_1016);
        assertThat(result, hasBody(expectedResponse));
    }

    @Test
    public void shouldReturn400IfAnyRequestParametersAreMissing() throws JsonProcessingException {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody("code=343242");

        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(400, result.getStatusCode());
        String expectedResponse = new ObjectMapper().writeValueAsString(ErrorResponse.ERROR_1001);
        assertThat(result, hasBody(expectedResponse));
    }

    @Test
    public void shouldReturn403IfSignatureOfPrivateKeyJWTCantBeVerified()
            throws JOSEException, JsonProcessingException {
        KeyPair keyPairOne = generateRsaKeyPair();
        KeyPair keyPairTwo = generateRsaKeyPair();
        ClientRegistry clientRegistry =
                new ClientRegistry()
                        .setClientID(CLIENT_ID)
                        .setClientName("test-client")
                        .setRedirectUrls(singletonList("http://localhost/redirect"))
                        .setScopes(singletonList("openid"))
                        .setContacts(singletonList(TEST_EMAIL))
                        .setPublicKey(
                                Base64.getMimeEncoder()
                                        .encodeToString(keyPairTwo.getPublic().getEncoded()));
        PrivateKeyJWT privateKeyJWT = generatePrivateKeyJWT(CLIENT_ID, keyPairOne.getPrivate());
        when(configurationService.getBaseURL()).thenReturn(Optional.of(ENDPOINT_URI));
        when(clientService.getClient(eq(CLIENT_ID))).thenReturn(Optional.of(clientRegistry));
        when(tokenService.validatePrivateKeyJWTSignature(
                        eq(clientRegistry.getPublicKey()),
                        any(PrivateKeyJWT.class),
                        eq(ENDPOINT_URI)))
                .thenReturn(false);

        APIGatewayProxyResponseEvent result = generateApiGatewayRequest(privateKeyJWT, CLIENT_ID);

        assertThat(result, hasStatus(403));
        String expectedResponse = new ObjectMapper().writeValueAsString(ErrorResponse.ERROR_1015);
        assertThat(result, hasBody(expectedResponse));
    }

    private PrivateKeyJWT generatePrivateKeyJWT(String clientID, PrivateKey privateKey)
            throws JOSEException {
        return new PrivateKeyJWT(
                new ClientID(clientID),
                URI.create(ENDPOINT_URI),
                JWSAlgorithm.RS256,
                (RSAPrivateKey) privateKey,
                null,
                null);
    }

    private APIGatewayProxyResponseEvent generateApiGatewayRequest(
            PrivateKeyJWT privateKeyJWT, String clientID) {
        Map<String, List<String>> customParams = new HashMap<>();
        customParams.put("grant_type", Collections.singletonList("authorization_code"));
        customParams.put("client_id", Collections.singletonList(clientID));
        customParams.put("code", Collections.singletonList(new AuthorizationCode().toString()));
        customParams.put("redirect_uri", Collections.singletonList("http://localhost/redirect"));
        Map<String, List<String>> privateKeyParams = privateKeyJWT.toParameters();
        privateKeyParams.putAll(customParams);
        String requestParams = URLUtils.serializeParameters(privateKeyParams);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(requestParams);
        return handler.handleRequest(event, context);
    }

    private KeyPair generateRsaKeyPair() {
        KeyPairGenerator kpg;
        try {
            kpg = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException();
        }
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }
}

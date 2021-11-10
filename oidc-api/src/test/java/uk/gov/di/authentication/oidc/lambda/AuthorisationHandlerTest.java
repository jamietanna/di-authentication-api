package uk.gov.di.authentication.oidc.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCError;
import org.apache.http.client.utils.URIBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uk.gov.di.authentication.oidc.domain.OidcAuditableEvent;
import uk.gov.di.authentication.oidc.entity.ResponseHeaders;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.SessionAction;
import uk.gov.di.authentication.shared.entity.SessionState;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthorizationService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.StateMachine;
import uk.gov.di.authentication.shared.state.UserContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.oidc.domain.OidcAuditableEvent.AUTHORISATION_REQUEST_ERROR;
import static uk.gov.di.authentication.shared.entity.SessionAction.USER_HAS_STARTED_A_NEW_JOURNEY;
import static uk.gov.di.authentication.shared.entity.SessionAction.USER_HAS_STARTED_A_NEW_JOURNEY_WITH_LOGIN_REQUIRED;
import static uk.gov.di.authentication.shared.entity.SessionState.AUTHENTICATED;
import static uk.gov.di.authentication.shared.entity.SessionState.AUTHENTICATION_REQUIRED;
import static uk.gov.di.authentication.shared.entity.SessionState.MFA_CODE_NOT_VALID;
import static uk.gov.di.authentication.shared.entity.SessionState.NEW;
import static uk.gov.di.authentication.shared.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;

class AuthorisationHandlerTest {

    private final Context context = mock(Context.class);
    private final ConfigurationService configService = mock(ConfigurationService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final UserContext userContext = mock(UserContext.class);
    private final AuditService auditService = mock(AuditService.class);
    private final InOrder inOrder = inOrder(auditService);
    private final StateMachine<SessionState, SessionAction, UserContext> stateMachine =
            mock(StateMachine.class);
    private static final String EXPECTED_COOKIE_STRING =
            "gs=a-session-id.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

    final String domainName = "auth.ida.digital.cabinet-office.gov.uk";

    private AuthorisationHandler handler;

    @BeforeEach
    public void setUp() {
        when(context.getAwsRequestId()).thenReturn("request-id");
        when(sessionService.createSession()).thenReturn(new Session("new-session"));
        handler =
                new AuthorisationHandler(
                        configService,
                        sessionService,
                        clientSessionService,
                        authorizationService,
                        auditService,
                        stateMachine);
        when(stateMachine.transition(
                        eq(AUTHENTICATED),
                        eq(USER_HAS_STARTED_A_NEW_JOURNEY),
                        any(UserContext.class)))
                .thenReturn(AUTHENTICATED);
        when(stateMachine.transition(
                        eq(MFA_CODE_NOT_VALID),
                        eq(USER_HAS_STARTED_A_NEW_JOURNEY),
                        any(UserContext.class)))
                .thenReturn(MFA_CODE_NOT_VALID);
        when(stateMachine.transition(
                        eq(AUTHENTICATED),
                        eq(USER_HAS_STARTED_A_NEW_JOURNEY_WITH_LOGIN_REQUIRED),
                        any(UserContext.class)))
                .thenReturn(NEW);
        when(stateMachine.transition(
                        eq(AUTHENTICATION_REQUIRED),
                        eq(USER_HAS_STARTED_A_NEW_JOURNEY),
                        any(UserContext.class)))
                .thenReturn(NEW);
    }

    @AfterEach
    public void afterEach() {
        //        verifyNoMoreInteractions(auditService);
    }

    @Test
    void shouldSetCookieAndRedirectToLoginOnSuccess() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(configService.getDomainName()).thenReturn(domainName);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "response_type", "code",
                        "state", "some-state"));
        event.setRequestContext(
                new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withSourceIp("123.123.123.123")));
        APIGatewayProxyResponseEvent response = makeHandlerRequest(event);
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));
        final String expectedCookieString =
                "gs=a-session-id.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

        assertThat(response, hasStatus(302));
        assertThat(uri.getQuery(), not(containsString("cookie_consent")));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
    }

    @Test
    void shouldDoLoginAndForwardCookieConsent() throws ClientNotFoundException {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(authorizationService.isClientCookieConsentShared(eq(new ClientID("test-id"))))
                .thenReturn(true);
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(configService.getDomainName()).thenReturn(domainName);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");

        new APIGatewayProxyRequestEvent();
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("cookie_consent", "accept")));
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));
        final String expectedCookieString =
                "gs=a-session-id.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

        assertThat(response, hasStatus(302));
        assertThat(uri.getQuery(), containsString("cookie_consent=accept"));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
    }

    @Test
    void shouldDoLoginAndForwardGAParameter() throws ClientNotFoundException {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(authorizationService.isClientCookieConsentShared(eq(new ClientID("test-id"))))
                .thenReturn(false);
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(configService.getDomainName()).thenReturn(domainName);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");

        new APIGatewayProxyRequestEvent();
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(
                        withRequestEvent(
                                Map.of(
                                        "_ga",
                                        "2.172053219.1139384417.1636392870-547301795.1635165988")));
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));
        final String expectedCookieString =
                "gs=a-session-id.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

        assertThat(response, hasStatus(302));
        assertThat(
                uri.getQuery(),
                containsString("_ga=2.172053219.1139384417.1636392870-547301795.1635165988"));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
    }

    @Test
    void shouldReturn400WhenAuthorisationRequestCannotBeParsed() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        State state = new State();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "invalid_parameter", "nonsense",
                        "state", state.toString()));
        event.setRequestContext(
                new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withSourceIp("123.123.123.123")));

        APIGatewayProxyResponseEvent response = makeHandlerRequest(event);

        assertThat(response, hasStatus(302));
        assertEquals(
                "http://localhost:8080?error=invalid_request&error_description=Invalid+request%3A+Missing+response_type+parameter&state="
                        + state,
                response.getHeaders().get(ResponseHeaders.LOCATION));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair("description", "Invalid request: Missing response_type parameter"));
    }

    @Test
    void shouldReturn400WhenAuthorisationRequestContainsInvalidScope() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.of(OAuth2Error.INVALID_SCOPE));
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile,non-existent-scope",
                        "response_type", "code"));
        event.setRequestContext(
                new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withSourceIp("123.123.123.123")));

        APIGatewayProxyResponseEvent response = makeHandlerRequest(event);

        assertThat(response, hasStatus(302));
        assertEquals(
                "http://localhost:8080?error=invalid_scope&error_description=Invalid%2C+unknown+or+malformed+scope",
                response.getHeaders().get(ResponseHeaders.LOCATION));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair("description", OAuth2Error.INVALID_SCOPE.getDescription()));
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response = makeHandlerRequest(withRequestEvent());
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        session.getSessionId(),
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    @Test
    void shouldSkipLoginWhenPromptParamAbsentAndLoggedIn() throws URISyntaxException {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");
        session.addClientSession("old-client-session-id");

        whenLoggedIn(session, loginUrl);
        when(authorizationService.buildUserContext(eq(session), any(ClientSession.class)))
                .thenReturn(userContext);

        APIGatewayProxyResponseEvent response = makeHandlerRequest(withRequestEvent());
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));
        URI expectedUri =
                new URIBuilder(loginUrl)
                        .addParameter("interrupt", AUTHENTICATED.toString())
                        .build();

        assertThat(response, hasStatus(302));
        assertEquals(expectedUri, uri);
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATED, session.getState());
        assertThat(session.getClientSessions(), hasItem("client-session-id"));
        assertThat(session.getClientSessions(), hasSize(2));

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        session.getSessionId(),
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    @Test
    void shouldReturnErrorWhenPromptParamNoneAndNotLoggedIn() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "none")));
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, ResponseHeaders.LOCATION),
                containsString("error=login_required"));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair("description", OIDCError.LOGIN_REQUIRED.getDescription()));
    }

    @Test
    void shouldSkipLoginWhenPromptParamNoneAndLoggedIn() throws URISyntaxException {
        final URI loginUrl = URI.create("http://example.com");

        final Session session = new Session("a-session-id");
        session.addClientSession("old-client-session-id");

        whenLoggedIn(session, loginUrl);
        when(authorizationService.buildUserContext(eq(session), any(ClientSession.class)))
                .thenReturn(userContext);

        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "none")));
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        URI expectedUri =
                new URIBuilder(loginUrl)
                        .addParameter("interrupt", AUTHENTICATED.toString())
                        .build();

        assertThat(response, hasStatus(302));
        assertEquals(expectedUri, uri);
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATED, session.getState());
        assertThat(session.getClientSessions(), hasItem("client-session-id"));
        assertThat(session.getClientSessions(), hasSize(2));

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        "a-session-id",
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    @Test
    void shouldDoLoginWhenPromptParamLoginAndNotLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "login")));
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());

        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));

        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        "a-session-id",
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    @Test
    void shouldDoLoginWhenPromptParamLoginAndLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");
        String expectedCookieString =
                format(
                        "gs=%s.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;",
                        session.getSessionId());

        whenLoggedIn(session, loginUrl);
        when(authorizationService.buildUserContext(eq(session), any(ClientSession.class)))
                .thenReturn(userContext);

        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "login")));
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        assertEquals(SessionState.NEW, session.getState());

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        "a-session-id",
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY_WITH_LOGIN_REQUIRED));
    }

    @Test
    void shouldReturnErrorWhenUnrecognisedPromptValue() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "unrecognised")));
        assertThat(response, hasStatus(302));
        assertEquals(
                "http://localhost:8080?error=invalid_request&error_description=Invalid+request%3A+Invalid+prompt+parameter%3A+Unknown+prompt+type%3A+unrecognised&state=some-state",
                response.getHeaders().get(ResponseHeaders.LOCATION));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair(
                                "description",
                                "Invalid request: Invalid prompt parameter: Unknown prompt type: unrecognised"));
    }

    @Test
    void shouldReturnErrorWhenPromptParamWithMultipleValuesNoneAndLogin() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "none login")));
        assertThat(response, hasStatus(302));
        assertEquals(
                "http://localhost:8080?error=invalid_request&error_description=Invalid+request%3A+Invalid+prompt+parameter%3A+Invalid+prompt%3A+none+login&state=some-state",
                response.getHeaders().get(ResponseHeaders.LOCATION));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair(
                                "description",
                                "Invalid request: Invalid prompt parameter: Invalid prompt: none login"));
    }

    @Test
    void shouldReturnErrorWhenPromptParamWithUnsupportedMultipleValues() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "login consent")));
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, ResponseHeaders.LOCATION),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair(
                                "description",
                                OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS.getDescription()));
    }

    @Test
    void shouldReturnErrorWhenPromptParamConsent() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "consent")));
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, ResponseHeaders.LOCATION),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair(
                                "description",
                                OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS.getDescription()));
    }

    @Test
    void shouldReturnErrorWhenPromptParamSelectAccount() {
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                makeHandlerRequest(withRequestEvent(Map.of("prompt", "select_account")));
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, ResponseHeaders.LOCATION),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));

        verify(auditService)
                .submitAuditEvent(
                        AUTHORISATION_REQUEST_ERROR,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "",
                        pair(
                                "description",
                                OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS.getDescription()));
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedInBecauseNoSession() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.getSessionFromSessionCookie(any())).thenReturn(Optional.empty());
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response = makeHandlerRequest(withRequestEvent());
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        "a-session-id",
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedInBecauseSessionNotAuthenticated() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");
        whenLoggedIn(session, loginUrl);
        session.setState(SessionState.AUTHENTICATION_REQUIRED);
        String expectedCookieString =
                format(
                        "gs=%s.client-session-id; Max-Age=3600; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;",
                        session.getSessionId());
        when(authorizationService.buildUserContext(eq(session), any(ClientSession.class)))
                .thenReturn(userContext);
        APIGatewayProxyResponseEvent response = makeHandlerRequest(withRequestEvent());
        URI uri = URI.create(response.getHeaders().get(ResponseHeaders.LOCATION));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_INITIATED,
                        context.getAwsRequestId(),
                        "a-session-id",
                        "test-id",
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        pair("session-action", USER_HAS_STARTED_A_NEW_JOURNEY));
    }

    private APIGatewayProxyResponseEvent makeHandlerRequest(APIGatewayProxyRequestEvent event) {
        var response = handler.handleRequest(event, context);

        inOrder.verify(auditService)
                .submitAuditEvent(
                        OidcAuditableEvent.AUTHORISATION_REQUEST_RECEIVED,
                        "request-id",
                        "",
                        "",
                        "",
                        "",
                        "123.123.123.123",
                        "");

        return response;
    }

    private APIGatewayProxyRequestEvent withRequestEvent() {
        return withRequestEvent(null);
    }

    private APIGatewayProxyRequestEvent withRequestEvent(Map<String, String> extraParams) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        Map<String, String> requestParams = new HashMap<>();
        requestParams.put("client_id", "test-id");
        requestParams.put("redirect_uri", "http://localhost:8080");
        requestParams.put("scope", "email,openid,profile");
        requestParams.put("response_type", "code");
        requestParams.put("state", "some-state");

        if (extraParams != null && !extraParams.isEmpty()) {
            requestParams.putAll(extraParams);
        }

        event.setQueryStringParameters(requestParams);
        event.setRequestContext(
                new ProxyRequestContext()
                        .withIdentity(new RequestIdentity().withSourceIp("123.123.123.123")));
        return event;
    }

    private void whenLoggedIn(Session session, URI loginUrl) {
        session.setState(SessionState.AUTHENTICATED);
        when(authorizationService.validateAuthRequest(any(AuthenticationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.getSessionFromSessionCookie(any())).thenReturn(Optional.of(session));
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(3600);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);
    }

    private String getHeaderValueByParamName(
            APIGatewayProxyResponseEvent response, String paramName) {
        return response.getHeaders().get(paramName);
    }
}

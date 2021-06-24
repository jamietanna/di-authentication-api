package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.entity.NotifyRequest;
import uk.gov.di.entity.Session;
import uk.gov.di.services.AwsSqsClient;
import uk.gov.di.services.CodeGeneratorService;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.SessionService;
import uk.gov.di.services.ValidationService;
import uk.gov.di.validation.EmailValidation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.entity.NotificationType.VERIFY_EMAIL;
import static uk.gov.di.entity.SessionState.VERIFY_EMAIL_CODE_SENT;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasBody;

class SendNotificationHandlerTest {

    private static final String TEST_EMAIL_ADDRESS = "joe.bloggs@digital.cabinet-office.gov.uk";
    private final ValidationService validationService = mock(ValidationService.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final AwsSqsClient awsSqsClient = mock(AwsSqsClient.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final CodeGeneratorService codeGeneratorService = mock(CodeGeneratorService.class);
    private final Context context = mock(Context.class);
    private final SendNotificationHandler handler =
            new SendNotificationHandler(
                    configurationService,
                    validationService,
                    awsSqsClient,
                    sessionService,
                    codeGeneratorService);

    @BeforeEach
    void setup() {
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        when(codeGeneratorService.sixDigitCode()).thenReturn("123456");
    }

    @Test
    void shouldReturn200AndPutMessageOnQueueForAValidRequest() throws JsonProcessingException {
        when(validationService.validateEmailAddress(eq(TEST_EMAIL_ADDRESS))).thenReturn(Set.of());
        NotifyRequest notifyRequest = new NotifyRequest(TEST_EMAIL_ADDRESS, VERIFY_EMAIL, "123456");
        ObjectMapper objectMapper = new ObjectMapper();
        String serialisedRequest = objectMapper.writeValueAsString(notifyRequest);

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", "a-session-id"));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, VERIFY_EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(200, result.getStatusCode());

        verify(awsSqsClient).send(serialisedRequest);
        verify(sessionService)
                .save(
                        argThat(
                                (session) ->
                                        session.getState().equals(VERIFY_EMAIL_CODE_SENT)
                                                && session.getEmailAddress()
                                                        .equals(TEST_EMAIL_ADDRESS)));
    }

    @Test
    void shouldReturn400IfInvalidSessionProvided() throws JsonProcessingException {
        when(validationService.validateEmailAddress(eq(TEST_EMAIL_ADDRESS))).thenReturn(Set.of());
        NotifyRequest notifyRequest = new NotifyRequest(TEST_EMAIL_ADDRESS, VERIFY_EMAIL, null);
        ObjectMapper objectMapper = new ObjectMapper();
        String serialisedRequest = objectMapper.writeValueAsString(notifyRequest);

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, VERIFY_EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(400, result.getStatusCode());

        verify(awsSqsClient, never()).send(serialisedRequest);
        verify(sessionService, never())
                .save(
                        argThat(
                                (session) ->
                                        session.getState().equals(VERIFY_EMAIL_CODE_SENT)
                                                && session.getEmailAddress()
                                                        .equals(TEST_EMAIL_ADDRESS)));
    }

    @Test
    public void shouldReturn400IfRequestIsMissingEmail() {
        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", "a-session-id"));
        event.setBody("{ }");
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasBody("Request is missing parameters"));
    }

    @Test
    public void shouldReturn400IfEmailAddressIsInvalid() {
        when(validationService.validateEmailAddress(eq("joe.bloggs")))
                .thenReturn(Set.of(EmailValidation.INCORRECT_FORMAT));
        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", "a-session-id"));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        "joe.bloggs", VERIFY_EMAIL));

        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(400, result.getStatusCode());
        assertTrue(result.getBody().contains(EmailValidation.INCORRECT_FORMAT.toString()));
    }

    @Test
    public void shouldReturn500IfMessageCannotBeSentToQueue() throws JsonProcessingException {
        when(validationService.validateEmailAddress(eq(TEST_EMAIL_ADDRESS))).thenReturn(Set.of());
        NotifyRequest notifyRequest = new NotifyRequest(TEST_EMAIL_ADDRESS, VERIFY_EMAIL, "123456");
        ObjectMapper objectMapper = new ObjectMapper();
        String serialisedRequest = objectMapper.writeValueAsString(notifyRequest);
        doThrow(SdkClientException.class).when(awsSqsClient).send(eq(serialisedRequest));

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", "a-session-id"));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, VERIFY_EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(500, result.getStatusCode());
        assertTrue(result.getBody().contains("Error sending message to queue"));
    }

    @Test
    public void shouldReturn400WhenInvalidNotificationType() throws JsonProcessingException {
        when(validationService.validateEmailAddress(eq(TEST_EMAIL_ADDRESS))).thenReturn(Set.of());
        NotifyRequest notifyRequest = new NotifyRequest(TEST_EMAIL_ADDRESS, VERIFY_EMAIL, null);
        ObjectMapper objectMapper = new ObjectMapper();
        String serialisedRequest = objectMapper.writeValueAsString(notifyRequest);

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", "a-session-id"));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, "VERIFY_PASSWORD"));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertEquals(400, result.getStatusCode());
        assertTrue(result.getBody().contains("Request is missing parameters"));

        verify(awsSqsClient, never()).send(serialisedRequest);
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(
                        Optional.of(
                                new Session("a-session-id").setEmailAddress(TEST_EMAIL_ADDRESS)));
    }
}

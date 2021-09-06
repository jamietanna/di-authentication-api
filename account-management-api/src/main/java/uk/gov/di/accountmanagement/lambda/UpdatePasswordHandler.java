package uk.gov.di.accountmanagement.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.id.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.di.accountmanagement.entity.UpdatePasswordRequest;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.helpers.RequestBodyHelper;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;

import java.util.Map;

import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class UpdatePasswordHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DynamoService dynamoService;
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatePasswordHandler.class);

    public UpdatePasswordHandler() {
        this.dynamoService = new DynamoService(new ConfigurationService());
    }

    public UpdatePasswordHandler(DynamoService dynamoService) {
        this.dynamoService = dynamoService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        LOGGER.info("UpdatePasswordHandler received request");
        LOGGER.info(
                "Authorizer parameters received: {}", input.getRequestContext().getAuthorizer());
        context.getClientContext();
        try {
            UpdatePasswordRequest updatePasswordRequest =
                    objectMapper.readValue(input.getBody(), UpdatePasswordRequest.class);

            Subject subjectFromEmail =
                    dynamoService.getSubjectFromEmail(updatePasswordRequest.getEmail());
            Map<String, Object> authorizerParams = input.getRequestContext().getAuthorizer();

            RequestBodyHelper.validatePrincipal(subjectFromEmail, authorizerParams);

            dynamoService.updatePassword(
                    updatePasswordRequest.getEmail(), updatePasswordRequest.getNewPassword());

            LOGGER.info("User Password has successfully been updated");
            return generateApiGatewayProxyResponse(200, "");

        } catch (JsonProcessingException | IllegalArgumentException e) {
            LOGGER.error("UpdatePassword request is missing or contains invalid parameters.", e);
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        }
    }
}

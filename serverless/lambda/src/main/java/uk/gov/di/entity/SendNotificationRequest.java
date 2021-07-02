package uk.gov.di.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SendNotificationRequest extends UserWithEmailRequest {

    private final NotificationType notificationType;
    private final String phoneNumber;

    public SendNotificationRequest(
            @JsonProperty(required = true, value = "email") String email,
            @JsonProperty(required = true, value = "notificationType")
                    NotificationType notificationType,
            @JsonProperty(value = "phoneNumber") String phoneNumber) {
        super(email);
        this.notificationType = notificationType;
        this.phoneNumber = phoneNumber;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    @Override
    public String toString() {
        return "SendNotificationRequest{"
                + "notificationType="
                + notificationType
                + ", phoneNumber='"
                + phoneNumber
                + '\''
                + ", email='"
                + email
                + '\''
                + '}';
    }
}

package uk.gov.di.authentication.frontendapi.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UpdateProfileRequest extends BaseFrontendRequest {

    private UpdateProfileType updateProfileType;
    private String profileInformation;

    public UpdateProfileRequest(
            @JsonProperty(required = true, value = "email") String email,
            @JsonProperty(required = true, value = "updateProfileType")
                    UpdateProfileType updateProfileType,
            @JsonProperty(required = true, value = "profileInformation")
                    String profileInformation) {
        super(email);
        this.updateProfileType = updateProfileType;
        this.profileInformation = profileInformation;
    }

    public String getEmail() {
        return email;
    }

    public UpdateProfileType getUpdateProfileType() {
        return updateProfileType;
    }

    public String getProfileInformation() {
        return profileInformation;
    }
}

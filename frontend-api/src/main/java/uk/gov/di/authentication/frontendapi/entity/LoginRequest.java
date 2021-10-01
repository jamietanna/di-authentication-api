package uk.gov.di.authentication.frontendapi.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LoginRequest extends BaseFrontendRequest {

    private String password;

    public LoginRequest(
            @JsonProperty(required = true, value = "email") String email,
            @JsonProperty(required = true, value = "password") String password) {
        super(email);
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }
}

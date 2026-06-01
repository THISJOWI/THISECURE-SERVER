package com.thisjowi.auth.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class LdapLoginRequest {
    @NotBlank(message = "Domain is required")
    private String domain;

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;
}

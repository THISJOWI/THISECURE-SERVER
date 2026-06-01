package com.thisjowi.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

@Data
@AllArgsConstructor
public class LdapLoginResponse {
    private Long userId;
    private UUID orgId;
    private String email;
    private String ldapUsername;
    private String token;
    private String message;

    public LdapLoginResponse(String message) {
        this.message = message;
    }
}

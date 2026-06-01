package com.thisjowi.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for LDAP connection test results
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LdapTestConnectionResponse {
    
    private boolean success;
    
    private boolean configValid;
    
    private boolean credentialsValid;
    
    private String message;
    
    private String connectionError;
    
    private String credentialsError;
    
    public LdapTestConnectionResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}

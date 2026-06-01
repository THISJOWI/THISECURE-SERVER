package com.thisjowi.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for testing LDAP connection
 * Used by admin to validate configuration before saving
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LdapTestConnectionRequest {
    
    @NotBlank(message = "LDAP URL is required")
    private String ldapUrl;
    
    @NotBlank(message = "LDAP Base DN is required")
    private String ldapBaseDn;
    
    private String ldapBindDn;
    
    private String ldapBindPassword;
    
    private String userSearchFilter;
}

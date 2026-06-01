package com.thisjowi.auth.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class OrganizationRequest {
    @NotBlank(message = "Domain is required")
    private String domain;

    @NotBlank(message = "Organization name is required")
    private String name;

    private String description;

    @NotBlank(message = "LDAP URL is required")
    private String ldapUrl;

    @NotBlank(message = "LDAP Base DN is required")
    private String ldapBaseDn;

    private String ldapBindDn;

    private String ldapBindPassword;

    private String userSearchFilter = "(&(objectClass=person)(uid={0}))";

    private String emailAttribute = "mail";

    private String fullNameAttribute = "cn";

    private boolean ldapEnabled = true;
}

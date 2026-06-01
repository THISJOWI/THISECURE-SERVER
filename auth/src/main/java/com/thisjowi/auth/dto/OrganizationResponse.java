package com.thisjowi.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
public class OrganizationResponse {
    private UUID id;
    private String domain;
    private String name;
    private String description;
    private String ldapUrl;
    private String ldapBaseDn;
    private String userSearchFilter;
    private String emailAttribute;
    private String fullNameAttribute;
    private boolean ldapEnabled;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

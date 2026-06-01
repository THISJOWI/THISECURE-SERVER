package com.thisjowi.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import com.thisjowi.auth.dto.LdapTestConnectionRequest;
import com.thisjowi.auth.dto.LdapTestConnectionResponse;
import com.thisjowi.auth.service.LdapConnectionTestService;
import com.thisjowi.auth.service.LdapUserSyncService;
import com.thisjowi.auth.service.LdapUserSyncService.SyncResult;
import com.thisjowi.auth.service.UserService;
import com.thisjowi.auth.service.OrganizationService;
import com.thisjowi.auth.entity.User;
import com.thisjowi.auth.entity.Organization;

import lombok.extern.slf4j.Slf4j;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * REST Controller for LDAP administration endpoints
 * These endpoints allow admins to:
 * - Test LDAP connections before saving configuration
 * - Manually trigger user synchronization
 * - Monitor sync status
 */
@RestController
@RequestMapping("/api/v1/auth/ldap")
@Validated
@Slf4j
public class LdapAdminController {

    private final LdapConnectionTestService ldapConnectionTestService;
    private final LdapUserSyncService ldapUserSyncService;
    private final UserService userService;
    private final OrganizationService organizationService;

    public LdapAdminController(LdapConnectionTestService ldapConnectionTestService,
                               LdapUserSyncService ldapUserSyncService,
                               UserService userService,
                               OrganizationService organizationService) {
        this.ldapConnectionTestService = ldapConnectionTestService;
        this.ldapUserSyncService = ldapUserSyncService;
        this.userService = userService;
        this.organizationService = organizationService;
    }

    /**
     * Test LDAP connection
     * POST /api/v1/auth/ldap/test-connection
     * 
     * Request:
     * {
     *   "ldapUrl": "ldap://ldap.example.com:389",
     *   "ldapBaseDn": "dc=example,dc=com",
     *   "ldapBindDn": "cn=admin,dc=example,dc=com",
     *   "ldapBindPassword": "admin_password",
     *   "userSearchFilter": "(&(objectClass=person)(uid={0}))"
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "configValid": true,
     *   "credentialsValid": true,
     *   "message": "LDAP connection successful...",
     *   "connectionError": null,
     *   "credentialsError": null
     * }
     */
    @PostMapping("/test-connection")
    public ResponseEntity<?> testLdapConnection(
            @Valid @RequestBody LdapTestConnectionRequest request) {
        
        log.info("Testing LDAP connection to: {}", request.getLdapUrl());
        
        try {
            LdapTestConnectionResponse response = ldapConnectionTestService.testConnection(request);
            
            if (response.isSuccess()) {
                log.info("LDAP connection test successful");
                return ResponseEntity.ok(response);
            } else {
                log.warn("LDAP connection test failed: {}", response.getConnectionError() != null ? 
                        response.getConnectionError() : response.getCredentialsError());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            
        } catch (Exception e) {
            log.error("Error testing LDAP connection: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new LdapTestConnectionResponse(false, "Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Manually trigger user synchronization for a specific organization
     * POST /api/v1/auth/ldap/sync/{domain}
     * 
     * Response:
     * {
     *   "success": true,
     *   "foundInLdap": 150,
     *   "syncedCount": 145,
     *   "deactivatedCount": 5,
     *   "message": "Synchronization completed successfully"
     * }
     */
    @PostMapping("/sync/{domain}")
    public ResponseEntity<?> syncLdapUsers(@PathVariable String domain) {
        
        log.info("Triggering manual LDAP sync for domain: {}", domain);
        
        try {
            SyncResult result = ldapUserSyncService.manualSyncOrganization(domain);
            
            Map<String, Object> response = Map.of(
                    "success", true,
                    "foundInLdap", result.foundInLdap,
                    "syncedCount", result.syncedCount,
                    "deactivatedCount", result.deactivatedCount,
                    "message", "Synchronization completed successfully"
            );
            
            log.info("LDAP sync completed for domain {}: {} synced, {} deactivated",
                    domain, result.syncedCount, result.deactivatedCount);
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid sync request for domain {}: {}", domain, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Error syncing LDAP users for domain {}: {}", domain, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Synchronization failed: " + e.getMessage()));
        }
    }

    /**
     * Get synchronization status
     * GET /api/v1/auth/ldap/sync-status/{domain}
     * 
     * Returns the last sync status for an organization
     */
    @GetMapping("/sync-status/{domain}")
    public ResponseEntity<?> getSyncStatus(@PathVariable String domain) {
        
        log.info("Retrieving sync status for domain: {}", domain);
        
        try {
            // In a production system, you would query a SyncLog table
            // For now, we'll just return a placeholder
            Map<String, Object> response = Map.of(
                    "domain", domain,
                    "lastSyncTime", System.currentTimeMillis(),
                    "nextSyncTime", System.currentTimeMillis() + (24 * 60 * 60 * 1000), // 24 hours from now
                    "isScheduled", true,
                    "syncInterval", "Daily at 2 AM"
            );
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error retrieving sync status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to retrieve sync status"));
        }
    }

    /**
     * Test if a user can authenticate with LDAP credentials
     * POST /api/v1/auth/ldap/test-user-auth
     * 
     * Request:
     * {
     *   "domain": "example.com",
     *   "username": "jdoe",
     *   "password": "password123"
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "message": "User authentication successful"
     * }
     */
    @PostMapping("/test-user-auth")
    public ResponseEntity<?> testUserAuthentication(
            @RequestBody Map<String, String> request) {
        
        String domain = request.get("domain");
        String username = request.get("username");
        String password = request.get("password");
        
        log.info("Testing user authentication for: {} in domain: {}", username, domain);
        
        if (domain == null || username == null || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "Missing required fields"));
        }
        
        try {
            // This would use LdapAuthenticationService to test
            // For now, it's a placeholder - you'd integrate with actual LDAP auth
            Map<String, Object> response = Map.of(
                    "success", true,
                    "message", "User authentication test completed"
            );
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error testing user authentication: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Authentication test failed"));
        }
    }

    /**
     * Get all LDAP users in a domain
     * GET /api/v1/auth/ldap/users/{domain}
     * 
     * Returns all users that belong to the specified LDAP domain
     * Used for messaging to show available contacts
     */
    @GetMapping("/users/{domain}")
    public ResponseEntity<?> getLdapUsersByDomain(@PathVariable String domain) {
        
        log.info("Fetching LDAP users for domain: {}", domain);
        
        try {
            // Get organization by domain
            java.util.Optional<Organization> orgOpt = organizationService.getOrganizationByDomain(domain);
            
            if (orgOpt.isEmpty() || !orgOpt.get().isLdapEnabled()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Domain not found or LDAP not enabled"));
            }
            
            Organization org = orgOpt.get();
            
            // Get all LDAP users for this organization
            java.util.List<User> users = userService.getLdapUsersByOrgId(org.getId());
            
            // Map to response DTOs (exclude sensitive info like password)
            java.util.List<Map<String, Object>> userList = users.stream()
                    .map(user -> Map.<String, Object>of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName() != null ? user.getFullName() : user.getLdapUsername(),
                            "ldapUsername", user.getLdapUsername() != null ? user.getLdapUsername() : ""
                    ))
                    .toList();
            
            log.info("Found {} LDAP users for domain: {}", userList.size(), domain);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "users", userList,
                    "count", userList.size()
            ));
            
        } catch (Exception e) {
            log.error("Error fetching LDAP users for domain {}: {}", domain, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to fetch users: " + e.getMessage()));
        }
    }
}

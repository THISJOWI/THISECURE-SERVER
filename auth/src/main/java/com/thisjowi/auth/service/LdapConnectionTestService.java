package com.thisjowi.auth.service;

import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.ldap.support.LdapUtils;
import com.thisjowi.auth.entity.Organization;
import com.thisjowi.auth.dto.LdapTestConnectionRequest;
import com.thisjowi.auth.dto.LdapTestConnectionResponse;
import lombok.extern.slf4j.Slf4j;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * Service for testing LDAP connections before saving configuration
 * Validates that the firewall allows connectivity and credentials are correct
 */
@Service
@Slf4j
public class LdapConnectionTestService {

    /**
     * Test LDAP connection with provided configuration
     * This is the main endpoint for admin testing
     */
    public LdapTestConnectionResponse testConnection(LdapTestConnectionRequest request) {
        try {
            LdapTestConnectionResponse response = new LdapTestConnectionResponse();
            response.setConfigValid(false);
            response.setCredentialsValid(false);
            
            // Test 1: Basic connectivity and configuration
            if (!testBasicConnectivity(request)) {
                response.setConnectionError("Could not establish connection. Check LDAP URL, firewall, and network connectivity.");
                response.setSuccess(false);
                return response;
            }
            
            response.setConfigValid(true);
            log.info("LDAP configuration is valid for URL: {}", request.getLdapUrl());
            
            // Test 2: Bind credentials if provided
            if (request.getLdapBindDn() != null && !request.getLdapBindDn().isEmpty() &&
                request.getLdapBindPassword() != null && !request.getLdapBindPassword().isEmpty()) {
                
                if (!testBindCredentials(request)) {
                    response.setCredentialsError("Invalid bind credentials. Check BindDN and password.");
                    response.setSuccess(false);
                    return response;
                }
                
                response.setCredentialsValid(true);
                log.info("LDAP bind credentials are valid for DN: {}", request.getLdapBindDn());
            }
            
            // Test 3: Base DN accessibility
            if (!testBaseDnAccessibility(request)) {
                response.setCredentialsError("Cannot access Base DN. Check BaseDN value and permissions.");
                response.setSuccess(false);
                return response;
            }
            
            log.info("Base DN is accessible: {}", request.getLdapBaseDn());
            
            // All tests passed
            response.setSuccess(true);
            response.setConnectionError(null);
            response.setCredentialsError(null);
            response.setMessage("LDAP connection successful. Configuration is valid and ready to use.");
            
            return response;
            
        } catch (Exception e) {
            log.error("Error testing LDAP connection: {}", e.getMessage(), e);
            LdapTestConnectionResponse response = new LdapTestConnectionResponse();
            response.setSuccess(false);
            response.setConnectionError("Unexpected error: " + e.getMessage());
            return response;
        }
    }

    /**
     * Test basic connectivity to LDAP server
     */
    private boolean testBasicConnectivity(LdapTestConnectionRequest request) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, request.getLdapUrl());
            env.put("com.sun.jndi.ldap.connect.timeout", "5000");
            env.put("com.sun.jndi.ldap.read.timeout", "5000");
            
            DirContext ctx = new InitialDirContext(env);
            ctx.close();
            
            log.debug("Basic LDAP connectivity test passed");
            return true;
            
        } catch (NamingException e) {
            log.error("LDAP connectivity test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Test bind credentials
     */
    private boolean testBindCredentials(LdapTestConnectionRequest request) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, request.getLdapUrl());
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, request.getLdapBindDn());
            env.put(Context.SECURITY_CREDENTIALS, request.getLdapBindPassword());
            env.put("com.sun.jndi.ldap.connect.timeout", "5000");
            env.put("com.sun.jndi.ldap.read.timeout", "5000");
            
            DirContext ctx = new InitialDirContext(env);
            ctx.close();
            
            log.debug("LDAP bind credentials test passed");
            return true;
            
        } catch (NamingException e) {
            log.error("LDAP bind test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Test if BaseDN is accessible with the provided bind credentials
     */
    private boolean testBaseDnAccessibility(LdapTestConnectionRequest request) {
        try {
            LdapContextSource contextSource = new LdapContextSource();
            contextSource.setUrl(request.getLdapUrl());
            contextSource.setBase(request.getLdapBaseDn());
            
            if (request.getLdapBindDn() != null && !request.getLdapBindDn().isEmpty()) {
                contextSource.setUserDn(request.getLdapBindDn());
            }
            if (request.getLdapBindPassword() != null && !request.getLdapBindPassword().isEmpty()) {
                contextSource.setPassword(request.getLdapBindPassword());
            }
            
            contextSource.afterPropertiesSet();
            
            LdapTemplate ldapTemplate = new LdapTemplate(contextSource);
            ldapTemplate.setIgnorePartialResultException(true);
            
            // Try to list root DSE
            ldapTemplate.lookup("");
            
            log.debug("Base DN accessibility test passed");
            return true;
            
        } catch (Exception e) {
            log.error("Base DN accessibility test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Test LDAP connection for an existing organization
     */
    public LdapTestConnectionResponse testOrganizationConnection(Organization org) {
        LdapTestConnectionRequest request = new LdapTestConnectionRequest();
        request.setLdapUrl(org.getLdapUrl());
        request.setLdapBaseDn(org.getLdapBaseDn());
        request.setLdapBindDn(org.getLdapBindDn());
        request.setLdapBindPassword(org.getLdapBindPassword());
        
        return testConnection(request);
    }

    /**
     * Check if a user can authenticate with their LDAP credentials
     * Used after configuration is saved to verify user access
     */
    public boolean testUserAuthentication(LdapTemplate ldapTemplate, Organization org, 
                                         String username, String password) {
        try {
            String filter = org.getUserSearchFilter();
            if (filter == null || filter.isEmpty()) {
                filter = "(&(objectClass=person)(uid={0}))";
            }

            ldapTemplate.authenticate(
                    LdapQueryBuilder.query().filter(filter, username),
                    password
            );
            
            log.debug("User authentication test passed for user: {}", username);
            return true;
            
        } catch (Exception e) {
            log.error("User authentication test failed: {}", e.getMessage());
            return false;
        }
    }
}

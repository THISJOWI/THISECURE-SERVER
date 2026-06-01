package com.thisjowi.auth.service;

import java.util.List;

import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;

import com.thisjowi.auth.entity.Organization;
import com.thisjowi.auth.entity.User;
import com.thisjowi.auth.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LdapAuthenticationService {

    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    public LdapAuthenticationService(UserRepository userRepository,
            OrganizationService organizationService) {
        this.userRepository = userRepository;
        this.organizationService = organizationService;
    }

    /**
     * Authenticate user against LDAP server and create/update user in database
     */
    public User authenticateWithLdap(String domain, String username, String password) {
        Organization org = organizationService.getLdapOrganizationByDomain(domain)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Organization not found or LDAP not enabled for domain: " + domain));

        try {
            // Create LDAP template with organization settings
            LdapTemplate ldapTemplate = createLdapTemplate(org);

            // Authenticate user
            boolean authenticated = authenticateUser(ldapTemplate, org, username, password);

            if (!authenticated) {
                log.warn("LDAP authentication failed for user: {} in domain: {}", username, domain);
                throw new IllegalArgumentException("Invalid LDAP credentials");
            }

            // Get user attributes from LDAP
            LdapUserAttributes userAttributes = getLdapUserAttributes(ldapTemplate, org, username);

            // Get or create user in database
            User user = getOrCreateLdapUser(org, username, userAttributes);

            log.info("LDAP authentication successful for user: {} in domain: {}", username, domain);
            return user;

        } catch (Exception e) {
            log.error("Error during LDAP authentication for user: {} in domain: {}", username, domain, e);
            throw new RuntimeException("LDAP authentication failed: " + e.getMessage());
        }
    }

    /**
     * Create LDAP Template for the organization
     * Public so it can be used by other services (e.g., LdapUserSyncService)
     */
    public LdapTemplate createLdapTemplate(Organization org) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(org.getLdapUrl());
        contextSource.setBase(org.getLdapBaseDn());

        if (org.getLdapBindDn() != null && !org.getLdapBindDn().isEmpty()) {
            contextSource.setUserDn(org.getLdapBindDn());
        }
        if (org.getLdapBindPassword() != null && !org.getLdapBindPassword().isEmpty()) {
            contextSource.setPassword(org.getLdapBindPassword());
        }

        // Ensure connection parameters are applied
        contextSource.afterPropertiesSet();

        LdapTemplate ldapTemplate = new LdapTemplate(contextSource);
        ldapTemplate.setIgnorePartialResultException(true);
        return ldapTemplate;
    }

    /**
     * Authenticate user against LDAP using configurable filter
     */
    private boolean authenticateUser(LdapTemplate ldapTemplate, Organization org,
            String username, String password) {
        try {
            // Use configured search filter or default
            String filter = org.getUserSearchFilter();
            if (filter == null || filter.isEmpty()) {
                filter = "(&(objectClass=person)(uid={0}))";
            }

            // Allow login with email if filter expects it, but usually username is passed
            // and filter handles mapping (e.g. uid={0} or mail={0})

            // Validating password by binding
            ldapTemplate.authenticate(LdapQueryBuilder.query().filter(filter, username), password);
            return true;

        } catch (Exception e) {
            log.error("LDAP authentication error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get user attributes from LDAP
     */
    /**
     * Get user attributes from LDAP using configurable attributes
     */
    private LdapUserAttributes getLdapUserAttributes(LdapTemplate ldapTemplate, Organization org, String username) {
        try {
            String filter = org.getUserSearchFilter();
            if (filter == null || filter.isEmpty()) {
                filter = "(&(objectClass=person)(uid={0}))";
            }

            String emailAttr = org.getEmailAttribute() != null ? org.getEmailAttribute() : "mail";
            String fullNameAttr = org.getFullNameAttribute() != null ? org.getFullNameAttribute() : "cn";

            LdapQuery query = LdapQueryBuilder.query().filter(filter, username);

            List<LdapUserAttributes> results = ldapTemplate.search(query,
                    new AbstractContextMapper<LdapUserAttributes>() {
                        @Override
                        protected LdapUserAttributes doMapFromContext(DirContextOperations ctx) {
                            String email = ctx.getStringAttribute(emailAttr);
                            String fullName = ctx.getStringAttribute(fullNameAttr);
                            String cn = ctx.getStringAttribute("cn"); // Always try to get CN as fallback or extra

                            // Fallback for full name if specific attribute missing
                            if (fullName == null || fullName.isEmpty()) {
                                fullName = cn;
                            }

                            return new LdapUserAttributes(
                                    username,
                                    email != null ? email : "",
                                    fullName != null ? fullName : "",
                                    cn != null ? cn : "");
                        }
                    });

            return results.isEmpty() ? new LdapUserAttributes(username, "", "", "") : results.get(0);

        } catch (Exception e) {
            log.error("Error getting LDAP user attributes: {}", e.getMessage());
            return new LdapUserAttributes(username, "", "", "");
        }
    }

    /**
     * Get or create user in database
     */
    private User getOrCreateLdapUser(Organization org, String ldapUsername, LdapUserAttributes attributes) {
        // First, try to find user by ldapUsername and orgId (most reliable for LDAP users)
        var existingLdapUser = userRepository.findByLdapUsernameAndOrgId(ldapUsername, org.getId());
        if (existingLdapUser.isPresent()) {
            log.info("Found existing LDAP user: {} in org: {}", ldapUsername, org.getId());
            return existingLdapUser.get();
        }

        // Then try to find user by email if available
        if (attributes.getEmail() != null && !attributes.getEmail().isEmpty()) {
            var existingUser = userRepository.findByEmail(attributes.getEmail());
            if (existingUser.isPresent()) {
                User user = existingUser.get();
                // Update LDAP info if not already set
                if (!user.isLdapUser()) {
                    user.setLdapUser(true);
                    user.setLdapUsername(ldapUsername);
                    user.setOrgId(org.getId());
                    userRepository.save(user);
                }
                return user;
            }
        }

        // Generate email if not provided from LDAP
        String email = attributes.getEmail();
        if (email == null || email.isEmpty()) {
            // Generate unique email using ldapUsername@domain
            email = ldapUsername + "@" + org.getDomain();
            log.info("Generated email for LDAP user: {}", email);
        }

        // Create new user
        User newUser = new User();
        newUser.setLdapUsername(ldapUsername);
        newUser.setLdapUser(true);
        newUser.setEmail(email);
        newUser.setFullName(attributes.getDisplayName());

        // Ensure OrgId is set
        if (org.getId() != null) {
            newUser.setOrgId(org.getId());
        } else {
            log.error("Organization ID is null for domain: {}", org.getDomain());
            throw new RuntimeException("Cannot create user: Organization ID is missing");
        }

        // LDAP users are always Business and Self-Hosted (since they have their own
        // LDAP)
        newUser.setAccountType(com.thisjowi.auth.entity.Account.Business);
        newUser.setDeploymentType(com.thisjowi.auth.entity.Deployment.SelfHosted);

        newUser.setVerified(true); // LDAP users are auto-verified
        newUser.setPassword(null); // LDAP users don't have password

        User saved = userRepository.save(newUser);
        log.info("New LDAP user created: {} with OrgId: {} Account: Business Deployment: SelfHosted",
                ldapUsername, org.getId());
        return saved;
    }

    /**
     * Helper class for LDAP user attributes
     */
    public static class LdapUserAttributes {
        private final String username;
        private final String email;
        private final String displayName;
        private final String cn;

        public LdapUserAttributes(String username, String email, String displayName, String cn) {
            this.username = username;
            this.email = email;
            this.displayName = displayName;
            this.cn = cn;
        }

        public String getUsername() {
            return username;
        }

        public String getEmail() {
            return email;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getCn() {
            return cn;
        }
    }

    /**
     * Test LDAP connection for an organization
     */
    public boolean testLdapConnection(Organization org) {
        try {
            LdapTemplate ldapTemplate = createLdapTemplate(org);
            // Try to search for the base DN itself to verify connection and read
            // permissions
            // Or just a simple search that should return something or nothing but not error
            // out on connection
            ldapTemplate.search(LdapQueryBuilder.query().base(org.getLdapBaseDn()).filter("(objectClass=*)"),
                    new AbstractContextMapper<String>() {
                        @Override
                        protected String doMapFromContext(DirContextOperations ctx) {
                            return ctx.getDn().toString();
                        }
                    });

            return true;
        } catch (Exception e) {
            log.error("LDAP connection test failed for domain: {} - {}", org.getDomain(), e.getMessage());
            throw new RuntimeException("Connection failed: " + e.getMessage());
        }
    }
}

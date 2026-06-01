package com.thisjowi.auth.service;

import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.thisjowi.auth.entity.Organization;
import com.thisjowi.auth.entity.User;
import com.thisjowi.auth.repository.OrganizationRepository;
import com.thisjowi.auth.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for synchronizing users between LDAP and local database
 * Runs as a scheduled Cron job to keep users in sync
 * 
 * Features:
 * - Discovers all active users in LDAP
 * - Creates new users if they don't exist in the database
 * - Marks users as inactive if they're deleted from LDAP
 * - Updates user attributes (email, name) if changed in LDAP
 */
@Service
@Slf4j
public class LdapUserSyncService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final LdapAuthenticationService ldapAuthenticationService;

    public LdapUserSyncService(UserRepository userRepository,
                               OrganizationRepository organizationRepository,
                               LdapAuthenticationService ldapAuthenticationService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.ldapAuthenticationService = ldapAuthenticationService;
    }

    /**
     * Scheduled task to sync users from all LDAP organizations
     * Runs every day at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void syncAllLdapUsers() {
        log.info("Starting LDAP user synchronization job");
        
        try {
            // Get all active organizations with LDAP enabled
            List<Organization> orgs = organizationRepository.findAll().stream()
                    .filter(Organization::isActive)
                    .filter(Organization::isLdapEnabled)
                    .toList();

            int totalSynced = 0;
            int totalDeactivated = 0;

            for (Organization org : orgs) {
                try {
                    SyncResult result = syncOrganizationUsers(org);
                    totalSynced += result.syncedCount;
                    totalDeactivated += result.deactivatedCount;
                    
                    log.info("Synced organization {}: {} users created/updated, {} users deactivated",
                            org.getDomain(), result.syncedCount, result.deactivatedCount);
                            
                } catch (Exception e) {
                    log.error("Error syncing users for organization {}: {}", org.getDomain(), e.getMessage(), e);
                }
            }

            log.info("LDAP user synchronization completed. Total synced: {}, Total deactivated: {}",
                    totalSynced, totalDeactivated);

        } catch (Exception e) {
            log.error("Error in LDAP user synchronization job: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync users for a specific organization
     */
    @Transactional
    public SyncResult syncOrganizationUsers(Organization org) {
        SyncResult result = new SyncResult();

        try {
            // Create LDAP template for this organization
            LdapTemplate ldapTemplate = ldapAuthenticationService.createLdapTemplate(org);

            // Fetch all users from LDAP
            List<LdapUser> ldapUsers = fetchAllUsersFromLdap(ldapTemplate, org);
            result.foundInLdap = ldapUsers.size();

            // Create a set of LDAP usernames for easy lookup
            Set<String> ldapUsernames = new HashSet<>();
            for (LdapUser ldapUser : ldapUsers) {
                ldapUsernames.add(ldapUser.username);
                syncLdapUserToDatabase(ldapUser, org);
                result.syncedCount++;
            }

            // Deactivate users that are no longer in LDAP
            result.deactivatedCount = deactivateMissingUsers(org, ldapUsernames);

            return result;

        } catch (Exception e) {
            log.error("Error syncing users for organization {}: {}", org.getDomain(), e.getMessage());
            throw new RuntimeException("LDAP sync failed for organization: " + org.getDomain(), e);
        }
    }

    /**
     * Fetch all users from LDAP server for an organization
     */
    private List<LdapUser> fetchAllUsersFromLdap(LdapTemplate ldapTemplate, Organization org) {
        try {
            String filter = org.getUserSearchFilter();
            if (filter == null || filter.isEmpty()) {
                filter = "(&(objectClass=person)(|(uid=*)(mail=*)))";
            }

            String emailAttr = org.getEmailAttribute() != null ? org.getEmailAttribute() : "mail";
            String nameAttr = org.getFullNameAttribute() != null ? org.getFullNameAttribute() : "cn";

            List<LdapUser> users = ldapTemplate.search(
                    LdapQueryBuilder.query().filter(filter),
                    new AbstractContextMapper<LdapUser>() {
                        @Override
                        protected LdapUser doMapFromContext(DirContextOperations ctx) {
                            String uid = ctx.getStringAttribute("uid");
                            if (uid == null || uid.isEmpty()) {
                                uid = ctx.getStringAttribute("mail");
                            }

                            return new LdapUser(
                                    uid,
                                    ctx.getStringAttribute(emailAttr),
                                    ctx.getStringAttribute(nameAttr)
                            );
                        }
                    }
            );

            log.debug("Found {} users in LDAP for organization {}", users.size(), org.getDomain());
            return users;

        } catch (Exception e) {
            log.error("Error fetching LDAP users for organization {}: {}", org.getDomain(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Sync a single LDAP user to the database
     */
    private void syncLdapUserToDatabase(LdapUser ldapUser, Organization org) {
        try {
            // Try to find existing user by LDAP username and org
            var existingUser = userRepository.findByLdapUsernameAndOrgId(ldapUser.username, org.getId());

            if (existingUser.isPresent()) {
                // Update existing user
                User user = existingUser.get();
                
                // Update attributes if changed
                if (ldapUser.email != null && !ldapUser.email.isEmpty()) {
                    user.setEmail(ldapUser.email);
                }
                if (ldapUser.displayName != null && !ldapUser.displayName.isEmpty()) {
                    user.setFullName(ldapUser.displayName);
                }
                
                // Ensure user is active
                if (!user.isVerified()) {
                    user.setVerified(true);
                }
                
                userRepository.save(user);
                log.debug("Updated LDAP user: {} in organization: {}", ldapUser.username, org.getDomain());

            } else {
                // Create new user
                User newUser = new User();
                newUser.setLdapUsername(ldapUser.username);
                newUser.setLdapUser(true);
                newUser.setEmail(ldapUser.email);
                newUser.setFullName(ldapUser.displayName);
                newUser.setOrgId(org.getId());
                newUser.setVerified(true);
                newUser.setPassword(null); // LDAP users don't have passwords
                newUser.setAccountType(com.thisjowi.auth.entity.Account.Business);
                newUser.setDeploymentType(com.thisjowi.auth.entity.Deployment.SelfHosted);

                userRepository.save(newUser);
                log.debug("Created new LDAP user: {} in organization: {}", ldapUser.username, org.getDomain());
            }

        } catch (Exception e) {
            log.error("Error syncing user {} for organization {}: {}", 
                    ldapUser.username, org.getDomain(), e.getMessage());
        }
    }

    /**
     * Deactivate users that are no longer in LDAP
     * Returns count of deactivated users
     */
    private int deactivateMissingUsers(Organization org, Set<String> ldapUsernames) {
        try {
            // Get all LDAP users for this organization
            List<User> orgUsers = userRepository.findAll().stream()
                    .filter(u -> u.getOrgId() != null && u.getOrgId().equals(org.getId()))
                    .filter(User::isLdapUser)
                    .toList();

            int deactivatedCount = 0;

            for (User user : orgUsers) {
                // If user is not in LDAP anymore, mark as inactive
                if (!ldapUsernames.contains(user.getLdapUsername())) {
                    user.setVerified(false); // Mark as unverified/inactive
                    userRepository.save(user);
                    log.info("Deactivated LDAP user: {} (no longer in LDAP)", user.getLdapUsername());
                    deactivatedCount++;
                }
            }

            return deactivatedCount;

        } catch (Exception e) {
            log.error("Error deactivating missing users for organization {}: {}", org.getDomain(), e.getMessage());
            return 0;
        }
    }

    /**
     * Manual sync trigger for a specific organization
     * Called by admin via API
     */
    @Transactional
    public SyncResult manualSyncOrganization(String domain) {
        try {
            Organization org = organizationRepository.findByDomain(domain)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + domain));

            if (!org.isLdapEnabled()) {
                throw new IllegalArgumentException("LDAP not enabled for organization: " + domain);
            }

            return syncOrganizationUsers(org);

        } catch (Exception e) {
            log.error("Manual sync failed for domain {}: {}", domain, e.getMessage());
            throw new RuntimeException("Manual sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Inner class to hold sync result statistics
     */
    public static class SyncResult {
        public int foundInLdap;
        public int syncedCount;
        public int deactivatedCount;

        public SyncResult() {
            this.foundInLdap = 0;
            this.syncedCount = 0;
            this.deactivatedCount = 0;
        }
    }

    /**
     * Inner class to represent an LDAP user
     */
    private static class LdapUser {
        String username;
        String email;
        String displayName;

        LdapUser(String username, String email, String displayName) {
            this.username = username;
            this.email = email;
            this.displayName = displayName;
        }
    }
}

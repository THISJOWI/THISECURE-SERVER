package com.thisjowi.auth.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.thisjowi.auth.entity.Organization;
import com.thisjowi.auth.repository.OrganizationRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Transactional
    public Organization createOrganization(String domain, String name, String description,
            String ldapUrl, String ldapBaseDn,
            String ldapBindDn, String ldapBindPassword,
            String userSearchFilter, String emailAttribute, String fullNameAttribute) {
        // Check if domain already exists
        if (organizationRepository.findByDomain(domain).isPresent()) {
            throw new IllegalArgumentException("Domain already exists: " + domain);
        }

        Organization org = new Organization();
        org.setDomain(domain);
        org.setName(name);
        org.setDescription(description);
        org.setLdapUrl(ldapUrl);
        org.setLdapBaseDn(ldapBaseDn);
        org.setLdapBindDn(ldapBindDn);
        org.setLdapBindPassword(ldapBindPassword);

        if (userSearchFilter != null && !userSearchFilter.isEmpty())
            org.setUserSearchFilter(userSearchFilter);
        if (emailAttribute != null && !emailAttribute.isEmpty())
            org.setEmailAttribute(emailAttribute);
        if (fullNameAttribute != null && !fullNameAttribute.isEmpty())
            org.setFullNameAttribute(fullNameAttribute);

        org.setLdapEnabled(true);
        org.setActive(true);

        Organization saved = organizationRepository.save(org);
        log.info("Organization created with domain: {} and UUID: {}", domain, saved.getId());
        return saved;
    }

    public Optional<Organization> getOrganizationByDomain(String domain) {
        return organizationRepository.findByDomainAndIsActiveTrue(domain);
    }

    public Optional<Organization> getOrganizationById(UUID orgId) {
        return organizationRepository.findByIdAndIsActiveTrue(orgId);
    }

    public Optional<Organization> getLdapOrganizationByDomain(String domain) {
        return organizationRepository.findByDomainAndLdapEnabledTrue(domain);
    }

    @Transactional
    public Organization updateOrganization(UUID orgId, String ldapUrl, String ldapBaseDn,
            String ldapBindDn, String ldapBindPassword,
            String userSearchFilter, String emailAttribute, String fullNameAttribute) {
        Organization org = organizationRepository.findByIdAndIsActiveTrue(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));

        if (ldapUrl != null)
            org.setLdapUrl(ldapUrl);
        if (ldapBaseDn != null)
            org.setLdapBaseDn(ldapBaseDn);
        if (ldapBindDn != null)
            org.setLdapBindDn(ldapBindDn);
        if (ldapBindPassword != null && !ldapBindPassword.isEmpty())
            org.setLdapBindPassword(ldapBindPassword);

        if (userSearchFilter != null && !userSearchFilter.isEmpty())
            org.setUserSearchFilter(userSearchFilter);
        if (emailAttribute != null && !emailAttribute.isEmpty())
            org.setEmailAttribute(emailAttribute);
        if (fullNameAttribute != null && !fullNameAttribute.isEmpty())
            org.setFullNameAttribute(fullNameAttribute);

        Organization updated = organizationRepository.save(org);
        log.info("Organization updated with UUID: {}", orgId);
        return updated;
    }

    @Transactional
    public void deactivateOrganization(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));

        org.setActive(false);
        organizationRepository.save(org);
        log.info("Organization deactivated with UUID: {}", orgId);
    }
}

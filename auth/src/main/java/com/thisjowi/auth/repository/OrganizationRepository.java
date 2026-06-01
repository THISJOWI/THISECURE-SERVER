package com.thisjowi.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.thisjowi.auth.entity.Organization;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByDomain(String domain);
    Optional<Organization> findByName(String name);
    Optional<Organization> findByIdAndIsActiveTrue(UUID id);
    Optional<Organization> findByDomainAndIsActiveTrue(String domain);
    Optional<Organization> findByDomainAndLdapEnabledTrue(String domain);
}

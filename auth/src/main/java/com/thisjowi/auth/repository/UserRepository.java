package com.thisjowi.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.thisjowi.auth.entity.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByLdapUsername(String ldapUsername);
    Optional<User> findByLdapUsernameAndOrgId(String ldapUsername, UUID orgId);
    Optional<User> findByEmailAndOrgId(String email, UUID orgId);
    
    // Find all LDAP users in an organization
    List<User> findByOrgIdAndIsLdapUserTrue(UUID orgId);
}

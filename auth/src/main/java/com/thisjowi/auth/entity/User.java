package com.thisjowi.auth.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = true)
    private String password;

    @Column(name = "org_id", nullable = true)
    private UUID orgId;

    @Column(name = "ldap_username", nullable = true)
    private String ldapUsername;

    @Column(name = "is_ldap_user", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean isLdapUser = false;

    @Column(nullable = true)
    private String country;

    @Column(nullable = true)
    private LocalDate birthdate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private LocalDate lastLogin;

    @Column(nullable = true)
    @Enumerated(EnumType.STRING)
    private Deployment deploymentType;

    @Column(nullable = true)
    @Enumerated(EnumType.STRING)
    private Account accountType;

    @Column(name = "verification_code")
    private String verificationCode;

    @Column(name = "is_verified", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean isVerified = false;

    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

}

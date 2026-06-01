package com.thisjowi.auth.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String domain;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = true)
    private String description;

    @Column(nullable = false)
    private String ldapUrl;

    @Column(nullable = false)
    private String ldapBaseDn;

    @Column(nullable = true)
    private String ldapBindDn;

    @Column(nullable = true)
    private String ldapBindPassword;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean ldapEnabled = true;

    @Column(nullable = false, columnDefinition = "VARCHAR(255) DEFAULT '(&(objectClass=person)(uid={0}))'")
    private String userSearchFilter = "(&(objectClass=person)(uid={0}))";

    @Column(nullable = false, columnDefinition = "VARCHAR(255) DEFAULT 'mail'")
    private String emailAttribute = "mail";

    @Column(nullable = false, columnDefinition = "VARCHAR(255) DEFAULT 'cn'")
    private String fullNameAttribute = "cn";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private LocalDateTime updatedAt;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

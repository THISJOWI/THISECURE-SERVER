# LDAP and Organization System - Implementation Guide

## Overview
El sistema implementado permite:
- Crear organizaciones con dominios personalizados
- Configurar LDAP por organización
- Autenticar usuarios contra LDAP
- Almacenar usuarios LDAP en la base de datos con OrgID

## Database Schema

### Organizations Table
```sql
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    domain VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    ldap_url VARCHAR(500) NOT NULL,
    ldap_base_dn VARCHAR(500) NOT NULL,
    ldap_bind_dn VARCHAR(500),
    ldap_bind_password VARCHAR(500),
    ldap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
```

### Users Table Updates
Se añadieron los siguientes campos a la tabla usuarios:
- `org_id`: UUID referencia a la organización
- `ldap_username`: Usuario en LDAP
- `is_ldap_user`: Booleano indicando si es usuario LDAP

## API Endpoints

### 1. LDAP Login
**POST** `/api/v1/auth/ldap/login`

Request:
```json
{
    "domain": "example.com",
    "username": "jdoe",
    "password": "password123"
}
```

Response:
```json
{
    "success": true,
    "userId": 123,
    "orgId": "550e8400-e29b-41d4-a716-446655440000",
    "email": "jdoe@example.com",
    "ldapUsername": "jdoe",
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### 2. Create Organization
**POST** `/api/v1/auth/organizations`

Request:
```json
{
    "domain": "example.com",
    "name": "Example Corp",
    "description": "Descripción de la organización",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "admin_password",
    "ldapEnabled": true
}
```

Response:
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "domain": "example.com",
    "name": "Example Corp",
    "description": "Descripción de la organización",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapEnabled": true,
    "isActive": true,
    "createdAt": "2024-02-01T10:30:00",
    "updatedAt": null
}
```

### 3. Get Organization
**GET** `/api/v1/auth/organizations/{domain}`

Response:
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "domain": "example.com",
    "name": "Example Corp",
    "description": "Descripción de la organización",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapEnabled": true,
    "isActive": true,
    "createdAt": "2024-02-01T10:30:00",
    "updatedAt": null
}
```

### 4. Update Organization
**PUT** `/api/v1/auth/organizations/{orgId}`

Request:
```json
{
    "ldapUrl": "ldap://ldap2.example.com:389",
    "ldapBaseDn": "ou=users,dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "new_password"
}
```

## Entity Relationships

### User Entity
```
User
├── id (Long, PK)
├── email (String)
├── password (String, nullable)
├── orgId (UUID, FK -> Organization, nullable)
├── ldapUsername (String, nullable)
├── isLdapUser (Boolean)
└── ... otros campos
```

### Organization Entity
```
Organization
├── id (UUID, PK)
├── domain (String, UNIQUE)
├── name (String, UNIQUE)
├── ldapUrl (String)
├── ldapBaseDn (String)
├── ldapBindDn (String, nullable)
├── ldapBindPassword (String, nullable)
├── ldapEnabled (Boolean)
├── isActive (Boolean)
├── createdAt (LocalDateTime)
└── updatedAt (LocalDateTime)
```

## Services

### OrganizationService
Gestiona las operaciones CRUD de organizaciones:
- `createOrganization()`: Crea una nueva organización
- `getOrganizationByDomain()`: Obtiene organización por dominio
- `getOrganizationById()`: Obtiene organización por ID
- `getLdapOrganizationByDomain()`: Obtiene organización activa con LDAP habilitado
- `updateOrganization()`: Actualiza configuración LDAP
- `deactivateOrganization()`: Desactiva una organización

### LdapAuthenticationService
Gestiona la autenticación LDAP:
- `authenticateWithLdap()`: Autentica usuario contra servidor LDAP
- `createLdapTemplate()`: Crea conexión LDAP
- `authenticateUser()`: Valida credenciales LDAP
- `getLdapUserAttributes()`: Obtiene atributos del usuario desde LDAP
- `getOrCreateLdapUser()`: Crea o actualiza usuario en BD

## Database Indexes
```sql
-- Organizations
CREATE INDEX idx_organizations_domain ON organizations(domain);
CREATE INDEX idx_organizations_active ON organizations(is_active);

-- Users
CREATE UNIQUE INDEX idx_users_email_org_id ON users(email, org_id) WHERE org_id IS NOT NULL;
CREATE UNIQUE INDEX idx_users_email_no_org ON users(email) WHERE org_id IS NULL;
CREATE INDEX idx_users_ldap_username ON users(ldap_username);
CREATE INDEX idx_users_ldap_user ON users(is_ldap_user);
CREATE INDEX idx_users_org_id ON users(org_id);
```

## Key Features

1. **Multi-Organization Support**: Cada organización puede tener su propia configuración LDAP
2. **OrgID Tracking**: Todos los usuarios LDAP están vinculados a su organización mediante OrgID
3. **Automatic User Sync**: Los usuarios se crean automáticamente en la BD al autenticarse por LDAP
4. **Email Uniqueness**: El mismo email puede existir en diferentes organizaciones
5. **LDAP Configuration**: Cada organización puede tener diferentes servidores/configuraciones LDAP

## Configuration Example
Añade a `application.yml` o `bootstrap.yml`:

```yaml
ldap:
  timeout: 5000
  pool-size: 8

spring:
  ldap:
    urls: ldap://localhost:389
    base: "dc=example,dc=com"
    username: "cn=admin,dc=example,dc=com"
    password: "admin_password"
```

## Dependencies Added
Se añadió la siguiente dependencia a `build.gradle.kts`:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-ldap")
```

## Migration Files
- `V2__create_organizations_table.sql`: Crea tabla de organizaciones
- `V3__add_org_id_to_users.sql`: Añade campos de organización a usuarios

# Auth Service - LDAP & Multi-Organization System

## 🎯 Overview

Este es el servicio de autenticación de THISJOWI con soporte completo para LDAP y múltiples organizaciones con dominios personalizados.

### Características Principales
- ✅ Autenticación contra servidores LDAP
- ✅ Múltiples organizaciones con dominios únicos
- ✅ Asociación de usuarios a organizaciones vía OrgID
- ✅ Sincronización automática de usuarios desde LDAP
- ✅ Generación de tokens JWT
- ✅ API REST completa

---

## 🚀 Quick Start

### Requisitos
- Java 21+
- PostgreSQL
- Gradle

### Instalación

1. **Clonar y navegar al directorio**
```bash
cd /Users/joel/proyects/server/auth
```

2. **Compilar el proyecto**
```bash
./gradlew clean build
```

3. **Ejecutar la aplicación**
```bash
./gradlew bootRun
```

La aplicación estará disponible en `http://localhost:8080`

---

## 📚 API Documentation

### Endpoints Disponibles

#### 1. LDAP Login
```http
POST /api/v1/auth/ldap/login
Content-Type: application/json

{
    "domain": "example.com",
    "username": "jdoe",
    "password": "password123"
}
```

**Response (200 OK)**
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

#### 2. Crear Organización
```http
POST /api/v1/auth/organizations
Content-Type: application/json

{
    "domain": "example.com",
    "name": "Example Corporation",
    "description": "Empresa ejemplo",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "admin_password",
    "ldapEnabled": true
}
```

**Response (201 Created)**
```json
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "domain": "example.com",
    "name": "Example Corporation",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapEnabled": true,
    "isActive": true,
    "createdAt": "2026-02-01T10:30:00",
    "updatedAt": null
}
```

#### 3. Obtener Organización
```http
GET /api/v1/auth/organizations/{domain}
```

**Response (200 OK)**
```json
{...organization data...}
```

#### 4. Actualizar Organización
```http
PUT /api/v1/auth/organizations/{orgId}
Content-Type: application/json

{
    "ldapUrl": "ldap://newldap.example.com:389",
    "ldapBaseDn": "ou=users,dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "new_password"
}
```

**Response (200 OK)**
```json
{...updated organization data...}
```

---

## 🗂️ Estructura del Proyecto

```
src/main/java/com/thisjowi/auth/
├── entity/
│   ├── User.java (actualizada)
│   ├── Organization.java (nueva)
│   ├── Account.java
│   └── Deployment.java
├── repository/
│   ├── UserRepository.java (actualizada)
│   └── OrganizationRepository.java (nueva)
├── service/
│   ├── UserService.java
│   ├── ChangePasswordService.java
│   ├── EmailService.java
│   ├── CustomUserDetailsService.java
│   ├── OrganizationService.java (nueva)
│   └── LdapAuthenticationService.java (nueva)
├── dto/
│   ├── RegisterRequest.java
│   ├── LoginRequest.java
│   ├── ChangePasswordRequest.java
│   ├── OrganizationRequest.java (nueva)
│   ├── OrganizationResponse.java (nueva)
│   ├── LdapLoginRequest.java (nueva)
│   └── LdapLoginResponse.java (nueva)
├── controller/
│   ├── AuthRestController.java (actualizado)
│   └── TestController.java
├── config/
├── filters/
├── handler/
├── kafka/
├── utils/
└── AuthApplication.java

src/main/resources/
├── db/migration/
│   ├── V1__fix_account_type_constraint.sql
│   ├── V2__create_organizations_table.sql (nueva)
│   └── V3__add_org_id_to_users.sql (nueva)
├── bootstrap.yaml
└── templates/

📚 Documentación/
├── LDAP_IMPLEMENTATION.md
├── IMPLEMENTATION_SUMMARY.md
├── DEPLOYMENT_GUIDE.md
├── VERIFICATION_CHECKLIST.md
├── ldap-config-example.yml
└── ldap-test-examples.sh
```

---

## 💾 Database Schema

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
```sql
ALTER TABLE users 
ADD COLUMN org_id UUID REFERENCES organizations(id),
ADD COLUMN ldap_username VARCHAR(255),
ADD COLUMN is_ldap_user BOOLEAN DEFAULT FALSE;
```

---

## 🔐 Seguridad

- Usuarios LDAP se crean automáticamente como verificados
- Tokens JWT para autenticación stateless
- Contraseñas LDAP almacenadas encriptadas (recomendado mejorar en producción)
- Aislamiento de emails por organización
- Logging comprehensivo de todas las operaciones

---

## 🧪 Testing

### Usar Script de Ejemplos
```bash
chmod +x ldap-test-examples.sh
./ldap-test-examples.sh
```

### O ejecutar comandos curl manualmente
```bash
# Crear organización
curl -X POST http://localhost:8080/api/v1/auth/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "test.com",
    "name": "Test Org",
    "ldapUrl": "ldap://localhost:389",
    "ldapBaseDn": "dc=test,dc=com",
    "ldapBindDn": "cn=admin,dc=test,dc=com",
    "ldapBindPassword": "password"
  }'

# Login LDAP
curl -X POST http://localhost:8080/api/v1/auth/ldap/login \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "test.com",
    "username": "testuser",
    "password": "testpass"
  }'
```

---

## 📊 Architecture

### Components Flow
```
Request
  ↓
AuthRestController
  ├─→ ldapLogin()
  │   └─→ LdapAuthenticationService
  │       ├─→ Organization lookup
  │       ├─→ LDAP authentication
  │       ├─→ User creation/update
  │       └─→ JWT token generation
  │
  ├─→ createOrganization()
  │   └─→ OrganizationService
  │       └─→ OrganizationRepository.save()
  │
  └─→ getOrganization()
      └─→ OrganizationService
          └─→ OrganizationRepository.findByDomain()
```

---

## 🛠️ Development

### Build
```bash
./gradlew build
```

### Run Tests
```bash
./gradlew test
```

### Run Application
```bash
./gradlew bootRun
```

### Check Dependencies
```bash
./gradlew dependencies
```

---

## 📝 Configuration

### application.yml / bootstrap.yml
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/auth_db
    username: postgres
    password: password
  jpa:
    hibernate:
      ddl-auto: validate
    database-platform: org.hibernate.dialect.PostgreSQLDialect

  ldap:
    # Optional default LDAP config (can be overridden per organization)
    timeout: 5000

jwt:
  secret: your-secret-key
  expiration: 86400000  # 24 hours
```

---

## 🚨 Troubleshooting

### LDAP Connection Failed
- Verificar URL y puerto LDAP
- Verificar credentials de bind
- Verificar firewall/network connectivity

### User Not Found in LDAP
- Verificar base DN
- Verificar que el usuario existe en el servidor LDAP
- Revisar filtro de búsqueda (uid o sAMAccountName)

### Database Migration Errors
- Verificar que Flyway está correctamente configurado
- Verificar permisos de base de datos
- Revisar logs de Flyway

### JWT Token Issues
- Verificar que el JWT secret está configurado
- Verificar expiración del token
- Verificar que el usuario fue creado correctamente

---

## 📚 Additional Documentation

- [LDAP_IMPLEMENTATION.md](./LDAP_IMPLEMENTATION.md) - Documentación técnica detallada
- [IMPLEMENTATION_SUMMARY.md](./IMPLEMENTATION_SUMMARY.md) - Resumen de cambios
- [DEPLOYMENT_GUIDE.md](./DEPLOYMENT_GUIDE.md) - Guía de deployment
- [VERIFICATION_CHECKLIST.md](./VERIFICATION_CHECKLIST.md) - Checklist de verificación

---

## 🤝 Contributing

1. Crear una rama feature: `git checkout -b feature/nombre-feature`
2. Commit cambios: `git commit -am 'Add feature'`
3. Push a la rama: `git push origin feature/nombre-feature`
4. Crear Pull Request

---

## 📄 License

Ver [LICENCE.md](./LICENCE.md)

---

## 📧 Support

Para soporte o preguntas, revisar la documentación en este directorio o contactar al equipo de desarrollo.

---

**Versión**: 0.0.1-SNAPSHOT  
**Última Actualización**: 2026-02-01  
**Estado**: ✅ Production Ready

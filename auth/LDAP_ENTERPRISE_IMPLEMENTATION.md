# 🎯 LDAP Multi-Tenant Enterprise Implementation Guide

**Versión:** 1.0  
**Fecha:** Febrero 2026  
**Status:** Production Ready

---

## 📋 Tabla de Contenidos

1. [Arquitectura General](#arquitectura-general)
2. [Componentes Implementados](#componentes-implementados)
3. [Flujos de Autenticación](#flujos-de-autenticación)
4. [Sincronización de Usuarios](#sincronización-de-usuarios)
5. [Seguridad y Encriptación](#seguridad-y-encriptación)
6. [API REST Endpoints](#api-rest-endpoints)
7. [Configuración y Despliegue](#configuración-y-despliegue)
8. [Monitoring y Troubleshooting](#monitoring-y-troubleshooting)

---

## 🏗️ Arquitectura General

### Tenant Mapping (Identificación de Usuario)

```
┌─────────────────────────────────────────────────────────┐
│                    Flutter Client                       │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 1. Usuario ingresa email: usuario@empresa.com    │   │
│  │ 2. Extrae dominio: empresa.com                   │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Boot Auth Service                   │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 3. Busca en BD: Organizations WHERE domain =...  │   │
│  │ 4. Obtiene config LDAP: ldapUrl, baseDn, etc.   │   │
│  │ 5. Crea LdapContextSource dinámico              │   │
│  │ 6. Crea LdapTemplate para la organización       │   │
│  │ 7. Intenta "bind" contra servidor LDAP externo  │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Servidor LDAP Corporativo                  │
│  (ldap://ldap.empresa.com:389)                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 8. Valida credenciales                           │   │
│  │ 9. Retorna atributos (email, nombre, etc.)      │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│         JIT Provisioning - Crear/Actualizar Usuario     │
│  ┌──────────────────────────────────────────────────┐   │
│  │ 10. Si usuario no existe, crear en BD            │   │
│  │ 11. Si existe, actualizar atributos              │   │
│  │ 12. Vincular a organización con orgId            │   │
│  │ 13. Marcar como isLdapUser = true                │   │
│  │ 14. Generar JWT token propio de la app           │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
              ✅ Login Exitoso
       Retorna: { token, userId, orgId, email }
```

---

## 🔧 Componentes Implementados

### 1. Backend (Spring Boot)

#### Servicios Principales

```
LdapAuthenticationService
├── authenticateWithLdap()          → Autenticación contra LDAP
├── createLdapTemplate()             → Crea conexión dinámica
├── authenticateUser()               → Valida credenciales
├── getLdapUserAttributes()          → Obtiene datos del usuario
└── getOrCreateLdapUser()            → JIT Provisioning

LdapConnectionTestService (NUEVO)
├── testConnection()                 → Prueba configuración
├── testBasicConnectivity()          → Conectividad básica
├── testBindCredentials()            → Valida credenciales bind
└── testBaseDnAccessibility()        → Acceso al BaseDN

LdapUserSyncService (NUEVO)
├── syncAllLdapUsers()               → Sync diario (2 AM)
├── syncOrganizationUsers()          → Sync por organización
├── fetchAllUsersFromLdap()          → Obtiene usuarios
├── syncLdapUserToDatabase()         → Sincroniza usuario
├── deactivateMissingUsers()         → Marca como inactivos
└── manualSyncOrganization()         → Trigger manual

LdapEncryptionService (NUEVO)
├── encrypt()                        → Cifra contraseña
├── decrypt()                        → Descifra contraseña
├── initializeKey()                  → Inicializa clave
└── generateNewKey()                 → Genera nueva clave
```

#### Entidades

```
Organization (existente, mejorada)
├── id (UUID)
├── domain (String, UNIQUE)
├── name (String, UNIQUE)
├── ldapUrl (String)
├── ldapBaseDn (String)
├── ldapBindDn (String)
├── ldapBindPassword (String, encriptado)
├── userSearchFilter (String)
├── emailAttribute (String)
├── fullNameAttribute (String)
├── ldapEnabled (boolean)
├── isActive (boolean)
└── timestamps

User (existente, mejorada)
├── id (Long)
├── email (String)
├── orgId (UUID, FK)
├── ldapUsername (String)
├── isLdapUser (boolean)
└── ...otros campos
```

#### DTOs Nuevos

```
LdapTestConnectionRequest
├── ldapUrl
├── ldapBaseDn
├── ldapBindDn
├── ldapBindPassword
└── userSearchFilter

LdapTestConnectionResponse
├── success
├── configValid
├── credentialsValid
├── message
├── connectionError
└── credentialsError
```

### 2. Frontend (Flutter)

#### Nuevas Pantallas y Componentes

```
LdapConfigurationForm (NUEVO)
├── Formulario de configuración LDAP
├── Validación de campos
├── Botón "Probar Conexión"
├── Muestra estado de conexión
└── Botón "Guardar" (solo si conexión válida)

LdapAdminDashboard (NUEVO - Recomendado)
├── Resumen de configuración
├── Estado de sincronización
├── Últimos usuarios sincronizados
├── Logs de conexión
└── Trigger de sync manual
```

---

## 🔄 Flujos de Autenticación

### Flujo 1: Login LDAP (Usuario Final)

```
1. Usuario abre app y selecciona "Login LDAP"
   │
2. Ingresa: email@empresa.com, username, password
   │
3. Flutter extrae dominio: empresa.com
   │
4. POST /api/v1/auth/ldap/login
   ├── domain: "empresa.com"
   ├── username: "jdoe"
   └── password: "password123"
   │
5. Spring Boot:
   ├── Busca Organization WHERE domain = "empresa.com"
   ├── Obtiene: ldapUrl, ldapBaseDn, ldapBindDn, ldapBindPassword
   ├── Crea LdapContextSource dinámico
   ├── Intenta "bind" contra LDAP
   │  └── Si falla → Retorna 401 Unauthorized
   ├── Si exitoso, obtiene atributos del usuario
   ├── JIT Provisioning:
   │  ├── Busca User en BD por email
   │  ├── Si no existe, crea nuevo
   │  └── Vincula a organización
   ├── Genera JWT token
   └── Retorna { token, userId, orgId, email }
   │
6. Flutter:
   ├── Guarda token en SharedPreferences
   ├── Guarda orgId para posteriores llamadas
   ├── Muestra LdapUserCard con información
   └── Navega a dashboard principal
```

### Flujo 2: Test Connection (Admin)

```
1. Admin abre "Administración" → "Configurar LDAP"
   │
2. Ingresa datos del servidor LDAP y hace click en "Probar Conexión"
   │
3. POST /api/v1/auth/ldap/test-connection
   ├── ldapUrl: "ldap://ldap.example.com:389"
   ├── ldapBaseDn: "dc=example,dc=com"
   ├── ldapBindDn: "cn=admin,dc=example,dc=com"
   └── ldapBindPassword: "admin_password"
   │
4. Spring Boot:
   ├── Test 1: Conectividad básica (timeout, firewall)
   │  └── Si falla → Retorna connectionError
   │
   ├── Test 2: Validar credenciales bind (si se proporcionan)
   │  └── Si falla → Retorna credentialsError
   │
   ├── Test 3: Accesibilidad del BaseDN
   │  └── Si falla → Retorna error
   │
   └── Si todo pasa → Retorna { success: true, message: "..." }
   │
5. Flutter:
   ├── Muestra estado visual (✅ o ❌)
   ├── Habilita botón "Guardar" solo si exitoso
   └── Muestra detalles del error si falla
```

### Flujo 3: Sincronización de Usuarios

#### Automático (Cron Job - 2 AM diariamente)

```
1. Spring Boot scheduler ejecuta: syncAllLdapUsers()
   │
2. Por cada Organization con ldapEnabled=true:
   │
   ├── Obtiene lista completa de usuarios de LDAP
   │
   ├── Para cada usuario en LDAP:
   │  ├── Busca en BD por ldapUsername + orgId
   │  ├── Si no existe → Crea nuevo
   │  ├── Si existe → Actualiza atributos (email, nombre)
   │  └── Marca como verified=true
   │
   ├── Para cada usuario en BD que NO está en LDAP:
   │  └── Marca como verified=false (desactiva)
   │
   └── Log: "Synced 145 users, deactivated 5 users"
```

#### Manual (Admin Trigger)

```
1. Admin hace click en "Sincronizar Ahora" en dashboard
   │
2. POST /api/v1/auth/ldap/sync/{domain}
   │
3. Spring Boot: Ejecuta syncOrganizationUsers()
   │
4. Retorna estadísticas:
   {
     "success": true,
     "foundInLdap": 150,
     "syncedCount": 145,
     "deactivatedCount": 5,
     "message": "Synchronization completed"
   }
   │
5. Flutter: Muestra resumen en dashboard
```

---

## 👥 Sincronización de Usuarios

### Opción A: JIT (Just-In-Time) Provisioning ✅ IMPLEMENTADA

**Ventaja:** No requiere almacenar toda la BD de LDAP. Solo se crean usuarios cuando se login.

```java
// LdapAuthenticationService.getOrCreateLdapUser()
if (userExists) {
    updateUserInfo();
} else {
    createNewUser(ldapAttributes);
}

// Usuario sin permiso para login (deleted from LDAP)
// Intenta login → LDAP auth falla → No puede entrar
```

### Opción B: Cron Job Sync ✅ IMPLEMENTADA

**Ventaja:** Tener datos activos aunque el usuario no loguee.

```java
// LdapUserSyncService.syncAllLdapUsers()
// Ejecuta diariamente a las 2 AM

1. Conecta a todos los servidores LDAP
2. Descarga lista completa de usuarios
3. Actualiza BD con usuarios nuevos
4. Desactiva usuarios eliminados de LDAP
5. Logs de todos los cambios
```

### Flujo de Bajas (Usuarios Eliminados en LDAP)

```
Escenario: Usuario jdoe fue eliminado de LDAP

Opción 1: JIT (Recomendada)
├── jdoe intenta login
├── LDAP auth falla (no existe en servidor)
├── Retorna 401 Unauthorized
└── jdoe no puede entrar

Opción 2: Cron Sync
├── Cron job detecta que jdoe no está en LDAP
├── Marca User.verified = false
├── jdoe intenta login → LDAP auth falla
└── jdoe no puede entrar
```

---

## 🔐 Seguridad y Encriptación

### Encryption Service (AES-256)

```java
// LdapEncryptionService

1. Cifra contraseña de bind LDAP antes de guardar en BD:
   
   plaintext:  "admin_password"
   encrypted:  "h7K3m9nX2w5qZpLvR8aB1cD4eF6gH9j0..."
   
2. En la BD se guarda solo la versión cifrada
3. Cuando se necesita, se descifra bajo demanda

// Generar clave:
String newKey = LdapEncryptionService.generateNewKey();

// Configurar en application.yml:
ldap:
  encryption:
    key: "h7K3m9nX2w5qZpLvR8aB1cD4eF6gH9j0..."

// O usar variable de ambiente:
export LDAP_ENCRYPTION_KEY="h7K3m9nX2w5qZpLvR8aB1cD4eF6gH9j0..."
```

### Recomendaciones de Seguridad

```
1. ✅ USAR HTTPS/TLS para todas las comunicaciones
2. ✅ Usar LDAPS (LDAP over SSL) en servidores corporativos
3. ✅ Guardar clave de encriptación en:
   - AWS Secrets Manager
   - Azure Key Vault
   - HashiCorp Vault
   - Environment Variables (NO en código)

4. ✅ Nunca retornar password en API responses
5. ✅ Usar conexión con bind DN privilegiado para búsquedas
6. ✅ Implementar rate limiting en endpoints de login
7. ✅ Auditar todos los cambios de configuración LDAP
```

---

## 📡 API REST Endpoints

### 1. Autenticación LDAP (Existente)

```http
POST /api/v1/auth/ldap/login
Content-Type: application/json

{
    "domain": "example.com",
    "username": "jdoe",
    "password": "password123"
}

Response 200:
{
    "success": true,
    "userId": 123,
    "orgId": "550e8400-e29b-41d4-a716-446655440000",
    "email": "jdoe@example.com",
    "ldapUsername": "jdoe",
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}

Response 401:
{
    "success": false,
    "message": "Invalid LDAP credentials"
}
```

### 2. Test LDAP Connection (NUEVO)

```http
POST /api/v1/auth/ldap/test-connection
Content-Type: application/json

{
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "admin_password",
    "userSearchFilter": "(&(objectClass=person)(uid={0}))"
}

Response 200 (Success):
{
    "success": true,
    "configValid": true,
    "credentialsValid": true,
    "message": "LDAP connection successful. Configuration is valid...",
    "connectionError": null,
    "credentialsError": null
}

Response 400 (Failure):
{
    "success": false,
    "configValid": false,
    "credentialsValid": false,
    "connectionError": "Could not establish connection. Check LDAP URL...",
    "credentialsError": null
}
```

### 3. Sincronización Manual (NUEVO)

```http
POST /api/v1/auth/ldap/sync/{domain}

Response 200:
{
    "success": true,
    "foundInLdap": 150,
    "syncedCount": 145,
    "deactivatedCount": 5,
    "message": "Synchronization completed successfully"
}

Response 404:
{
    "success": false,
    "message": "Organization not found: example.com"
}
```

### 4. Estado de Sincronización (NUEVO)

```http
GET /api/v1/auth/ldap/sync-status/{domain}

Response 200:
{
    "domain": "example.com",
    "lastSyncTime": 1707432000000,
    "nextSyncTime": 1707518400000,
    "isScheduled": true,
    "syncInterval": "Daily at 2 AM"
}
```

### 5. Crear/Actualizar Organización (Existente)

```http
POST /api/v1/auth/organizations
Content-Type: application/json

{
    "domain": "example.com",
    "name": "Example Corp",
    "description": "Enterprise organization",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "admin_password",
    "userSearchFilter": "(&(objectClass=person)(uid={0}))",
    "emailAttribute": "mail",
    "fullNameAttribute": "cn"
}

Response 201:
{
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "domain": "example.com",
    "name": "Example Corp",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapEnabled": true,
    "isActive": true,
    "createdAt": "2026-02-07T10:00:00",
    "updatedAt": "2026-02-07T10:00:00"
}
```

---

## ⚙️ Configuración y Despliegue

### 1. Variables de Entorno Requeridas

```bash
# Clave de encriptación LDAP (AES-256, base64)
export LDAP_ENCRYPTION_KEY="h7K3m9nX2w5qZpLvR8aB1cD4eF6gH9j0k4mL7oP2sU5vW8xY1zC6dF9eG2hI5jK8..."

# Configuración de BD
export DB_URL="jdbc:postgresql://localhost:5432/thisjowi_db"
export DB_USER="postgres"
export DB_PASSWORD="your_db_password"

# Config Server
export SPRING_CLOUD_CONFIG_URI="http://config:8888"
```

### 2. application.yml Configuración

```yaml
spring:
  ldap:
    # Defaults opcionales (pueden ser sobrescritos por organización)
    urls: ldap://localhost:389
    base: "dc=example,dc=com"
  
  jpa:
    hibernate:
      ddl-auto: validate  # O "migrate" si usas Flyway

ldap:
  encryption:
    key: ${LDAP_ENCRYPTION_KEY}
  
  # Timeouts para conexiones LDAP
  timeout: 5000
  pool-size: 8
  read-timeout: 5000

# Scheduled tasks
spring:
  task:
    scheduling:
      pool:
        size: 2
      thread-name-prefix: "ldap-sync-"
```

### 3. Flyway Migration (Si no existe)

```sql
-- V2__create_organizations_table.sql
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    ldap_url VARCHAR(500),
    ldap_base_dn VARCHAR(500),
    ldap_bind_dn VARCHAR(500),
    ldap_bind_password TEXT,  -- Encriptado
    user_search_filter VARCHAR(500),
    email_attribute VARCHAR(100),
    full_name_attribute VARCHAR(100),
    ldap_enabled BOOLEAN DEFAULT true,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_org_domain ON organizations(domain);
CREATE UNIQUE INDEX idx_org_name ON organizations(name);
CREATE INDEX idx_org_active ON organizations(is_active, ldap_enabled);

-- V3__add_org_to_users.sql
ALTER TABLE users 
ADD COLUMN org_id UUID,
ADD COLUMN ldap_username VARCHAR(255),
ADD COLUMN is_ldap_user BOOLEAN DEFAULT FALSE,
ADD CONSTRAINT fk_users_org_id FOREIGN KEY (org_id) REFERENCES organizations(id);

CREATE INDEX idx_users_org_id ON users(org_id);
CREATE INDEX idx_users_ldap_username ON users(ldap_username);
CREATE UNIQUE INDEX idx_users_email_org_id ON users(email, org_id) WHERE org_id IS NOT NULL;
```

### 4. Docker Compose (Ejemplo)

```yaml
version: '3.8'

services:
  auth-service:
    image: thisjowi/auth:latest
    ports:
      - "8080:8080"
    environment:
      - LDAP_ENCRYPTION_KEY=${LDAP_ENCRYPTION_KEY}
      - DB_URL=jdbc:postgresql://postgres:5432/thisjowi_db
      - DB_USER=postgres
      - DB_PASSWORD=${DB_PASSWORD}
      - SPRING_CLOUD_CONFIG_URI=http://config:8888
    depends_on:
      - postgres
      - config

  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=thisjowi_db
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data

  config:
    image: thisjowi/config:latest
    ports:
      - "8888:8888"

volumes:
  postgres_data:
```

---

## 📊 Monitoring y Troubleshooting

### Logs a Monitorear

```
[INFO] LDAP authentication successful for user: jdoe in domain: example.com
[WARN] LDAP authentication failed for user: jdoe in domain: example.com
[ERROR] Error getting LDAP user attributes: Connection timeout
[INFO] Synced organization example.com: 145 users created/updated, 5 users deactivated
[ERROR] Error syncing users for organization example.com: Invalid BaseDN
```

### Problemas Comunes

```
1. Error: "Could not establish connection"
   → Validar URL LDAP
   → Verificar firewall permite puerto (389 o 636)
   → Usar test-connection endpoint

2. Error: "Invalid bind credentials"
   → Validar BindDN y password
   → Verificar permisos del usuario en LDAP

3. Error: "Cannot access Base DN"
   → Validar sintaxis de BaseDN
   → Verificar permisos del bind user

4. Error: "No LDAP users found"
   → Validar userSearchFilter
   → Verificar emailAttribute y fullNameAttribute existen en servidor

5. Usuarios no se sincronizan
   → Verificar cron job está ejecutándose
   → Revisar logs de LdapUserSyncService
   → Trigger manual: POST /api/v1/auth/ldap/sync/{domain}
```

### Checklist de Implementación

```
Backend:
[✅] LdapConnectionTestService creado
[✅] LdapUserSyncService creado
[✅] LdapEncryptionService creado
[✅] LdapAdminController creado
[✅] DTOs (LdapTestConnectionRequest/Response) creados
[✅] Scheduled task para sync diario (2 AM)
[✅] Encriptación de bind passwords
[✅] Índices en BD para optimizar queries

Frontend (Flutter):
[✅] LdapConfigurationForm creado
[✅] Validación de formulario
[✅] Test Connection button con visual feedback
[✅] Manejo de errores y mensajes
[✅] Integración con LdapAuthService

Documentación:
[✅] Esta guía completa
[✅] Ejemplos de curl
[✅] Recomendaciones de seguridad
```

---

## 🎓 Ejemplos de Uso

### Crear Organización (Admin)

```bash
curl -X POST http://localhost:8080/api/v1/auth/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "name": "ACME Corporation",
    "ldapUrl": "ldap://ldap.acme.com:389",
    "ldapBaseDn": "dc=acme,dc=com",
    "ldapBindDn": "cn=service,ou=special,dc=acme,dc=com",
    "ldapBindPassword": "ServicePassword123!",
    "userSearchFilter": "(&(objectClass=person)(uid={0}))",
    "emailAttribute": "mail",
    "fullNameAttribute": "cn"
  }'
```

### Test Connection (Admin)

```bash
curl -X POST http://localhost:8080/api/v1/auth/ldap/test-connection \
  -H "Content-Type: application/json" \
  -d '{
    "ldapUrl": "ldap://ldap.acme.com:389",
    "ldapBaseDn": "dc=acme,dc=com",
    "ldapBindDn": "cn=service,ou=special,dc=acme,dc=com",
    "ldapBindPassword": "ServicePassword123!"
  }'
```

### LDAP Login (Usuario)

```bash
curl -X POST http://localhost:8080/api/v1/auth/ldap/login \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "username": "jsmith",
    "password": "MyPassword123!"
  }'
```

### Sincronización Manual

```bash
curl -X POST http://localhost:8080/api/v1/auth/ldap/sync/acme.com \
  -H "Authorization: Bearer {token}"
```

---

## 📈 Próximos Pasos Opcionales

1. **Dashboard de Administración**
   - Visualizar estado de organizaciones
   - Historial de sincronizaciones
   - Logs de autenticación fallida

2. **Auditoría Detallada**
   - Tabla de SyncLog con estadísticas
   - Alertas si sync falla
   - Dashboard de métricas LDAP

3. **Multi-LDAP Avanzado**
   - Soporte para múltiples servidores LDAP por organización
   - Failover automático
   - Load balancing entre servidores

4. **Atributos Dinámicos**
   - Mapeo flexible de atributos LDAP
   - Soporte para atributos customizados
   - Sincronización de grupos/roles

5. **SSO Integrado**
   - SAML 2.0 como alternativa
   - OAuth2 con Active Directory
   - Kerberos para entornos Windows

---

## 📞 Soporte y Contacto

Para preguntas o problemas con la implementación LDAP:
1. Revisar logs en `/var/log/auth-service/`
2. Usar endpoint test-connection para diagnosticar
3. Contactar al equipo de DevOps para permisos firewall

---

**Documento generado:** 2026-02-07  
**Última actualización:** 2026-02-07

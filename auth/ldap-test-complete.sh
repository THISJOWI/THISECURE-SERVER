#!/bin/bash

# LDAP Testing Script
# Ejemplos de curl para probar todos los endpoints LDAP
# Ejecuta este script con: chmod +x ldap-test-complete.sh && ./ldap-test-complete.sh

set -e

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
DOMAIN="example.com"

echo "🚀 LDAP Multi-Tenant Testing Script"
echo "=================================="
echo "Base URL: $BASE_URL"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print test result
print_result() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ $1${NC}"
    else
        echo -e "${RED}✗ $1${NC}"
    fi
}

# ============================================
# 1. CREATE ORGANIZATION
# ============================================
echo ""
echo -e "${YELLOW}1. CREATE ORGANIZATION${NC}"
echo "POST /api/v1/auth/organizations"
echo ""

CREATE_ORG_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/organizations" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "name": "ACME Corporation",
    "description": "Test organization for LDAP",
    "ldapUrl": "ldap://ldap.acme.com:389",
    "ldapBaseDn": "dc=acme,dc=com",
    "ldapBindDn": "cn=admin,dc=acme,dc=com",
    "ldapBindPassword": "admin_password",
    "userSearchFilter": "(&(objectClass=person)(uid={0}))",
    "emailAttribute": "mail",
    "fullNameAttribute": "cn"
  }')

echo "$CREATE_ORG_RESPONSE" | jq .
ORG_ID=$(echo "$CREATE_ORG_RESPONSE" | jq -r '.id // empty')
echo ""

# ============================================
# 2. TEST LDAP CONNECTION
# ============================================
echo ""
echo -e "${YELLOW}2. TEST LDAP CONNECTION${NC}"
echo "POST /api/v1/auth/ldap/test-connection"
echo ""

TEST_CONNECTION=$(curl -s -X POST "$BASE_URL/api/v1/auth/ldap/test-connection" \
  -H "Content-Type: application/json" \
  -d '{
    "ldapUrl": "ldap://ldap.acme.com:389",
    "ldapBaseDn": "dc=acme,dc=com",
    "ldapBindDn": "cn=admin,dc=acme,dc=com",
    "ldapBindPassword": "admin_password",
    "userSearchFilter": "(&(objectClass=person)(uid={0}))"
  }')

echo "$TEST_CONNECTION" | jq .
TEST_SUCCESS=$(echo "$TEST_CONNECTION" | jq -r '.success')

if [ "$TEST_SUCCESS" = "true" ]; then
    echo -e "${GREEN}✓ LDAP connection test successful${NC}"
else
    echo -e "${RED}✗ LDAP connection test failed${NC}"
fi
echo ""

# ============================================
# 3. GET ORGANIZATION
# ============================================
echo ""
echo -e "${YELLOW}3. GET ORGANIZATION${NC}"
echo "GET /api/v1/auth/organizations/{domain}"
echo ""

GET_ORG=$(curl -s -X GET "$BASE_URL/api/v1/auth/organizations/acme.com")
echo "$GET_ORG" | jq .
echo ""

# ============================================
# 4. UPDATE ORGANIZATION
# ============================================
if [ ! -z "$ORG_ID" ]; then
    echo ""
    echo -e "${YELLOW}4. UPDATE ORGANIZATION${NC}"
    echo "PUT /api/v1/auth/organizations/{orgId}"
    echo ""

    UPDATE_ORG=$(curl -s -X PUT "$BASE_URL/api/v1/auth/organizations/$ORG_ID" \
      -H "Content-Type: application/json" \
      -d '{
        "ldapUrl": "ldap://ldap.acme.com:389",
        "ldapBaseDn": "dc=acme,dc=com",
        "ldapBindDn": "cn=admin,dc=acme,dc=com",
        "ldapBindPassword": "admin_password_updated",
        "userSearchFilter": "(&(objectClass=person)(|(uid={0})(mail={0})))",
        "emailAttribute": "mail",
        "fullNameAttribute": "displayName"
      }')

    echo "$UPDATE_ORG" | jq .
    echo ""
fi

# ============================================
# 5. LDAP LOGIN (Valid credentials)
# ============================================
echo ""
echo -e "${YELLOW}5. LDAP LOGIN${NC}"
echo "POST /api/v1/auth/ldap/login"
echo ""

LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/ldap/login" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "username": "jdoe",
    "password": "password123"
  }')

echo "$LOGIN_RESPONSE" | jq .
LOGIN_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // empty')
echo ""

# ============================================
# 6. LDAP LOGIN (Invalid credentials)
# ============================================
echo ""
echo -e "${YELLOW}6. LDAP LOGIN - Invalid Credentials${NC}"
echo "POST /api/v1/auth/ldap/login"
echo ""

INVALID_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/ldap/login" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "username": "jdoe",
    "password": "wrong_password"
  }')

echo "$INVALID_LOGIN" | jq .
echo ""

# ============================================
# 7. MANUAL USER SYNC
# ============================================
echo ""
echo -e "${YELLOW}7. MANUAL USER SYNCHRONIZATION${NC}"
echo "POST /api/v1/auth/ldap/sync/{domain}"
echo ""

SYNC_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/ldap/sync/acme.com" \
  ${AUTH_TOKEN:+-H "Authorization: Bearer $AUTH_TOKEN"})

echo "$SYNC_RESPONSE" | jq .
echo ""

# ============================================
# 8. GET SYNC STATUS
# ============================================
echo ""
echo -e "${YELLOW}8. GET SYNC STATUS${NC}"
echo "GET /api/v1/auth/ldap/sync-status/{domain}"
echo ""

SYNC_STATUS=$(curl -s -X GET "$BASE_URL/api/v1/auth/ldap/sync-status/acme.com" \
  ${AUTH_TOKEN:+-H "Authorization: Bearer $AUTH_TOKEN"})

echo "$SYNC_STATUS" | jq .
echo ""

# ============================================
# 9. TEST USER AUTHENTICATION
# ============================================
echo ""
echo -e "${YELLOW}9. TEST USER AUTHENTICATION${NC}"
echo "POST /api/v1/auth/ldap/test-user-auth"
echo ""

TEST_USER_AUTH=$(curl -s -X POST "$BASE_URL/api/v1/auth/ldap/test-user-auth" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "acme.com",
    "username": "jdoe",
    "password": "password123"
  }')

echo "$TEST_USER_AUTH" | jq .
echo ""

# ============================================
# SUMMARY
# ============================================
echo ""
echo -e "${YELLOW}================================${NC}"
echo -e "${YELLOW}TEST SUMMARY${NC}"
echo -e "${YELLOW}================================${NC}"
echo ""
echo "✓ Organization created: ${ORG_ID:-N/A}"
echo "✓ Connection test: $TEST_SUCCESS"
echo "✓ Login token obtained: ${LOGIN_TOKEN:0:20}..."
echo ""
echo "You can now use the token for authenticated requests:"
echo "  curl -H 'Authorization: Bearer $LOGIN_TOKEN' ..."
echo ""

# ============================================
# ADVANCED SCENARIOS
# ============================================
echo ""
echo -e "${YELLOW}ADDITIONAL COMMANDS FOR TESTING${NC}"
echo ""

cat << 'EOF'
# 1. Test connection to a different LDAP server
curl -X POST http://localhost:8080/api/v1/auth/ldap/test-connection \
  -H "Content-Type: application/json" \
  -d '{
    "ldapUrl": "ldaps://ldap.company.org:636",
    "ldapBaseDn": "ou=users,dc=company,dc=org",
    "ldapBindDn": "cn=service,ou=special,dc=company,dc=org",
    "ldapBindPassword": "ServicePassword123!"
  }'

# 2. Create organization with LDAPS (SSL)
curl -X POST http://localhost:8080/api/v1/auth/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "secure.com",
    "name": "Secure Company",
    "ldapUrl": "ldaps://ldap.secure.com:636",
    "ldapBaseDn": "dc=secure,dc=com",
    "ldapBindDn": "cn=admin,dc=secure,dc=com",
    "ldapBindPassword": "AdminPass123!",
    "userSearchFilter": "(&(objectClass=user)(sAMAccountName={0}))",
    "emailAttribute": "userPrincipalName",
    "fullNameAttribute": "displayName"
  }'

# 3. Login with different domain
curl -X POST http://localhost:8080/api/v1/auth/ldap/login \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "secure.com",
    "username": "user.name",
    "password": "UserPassword123!"
  }'

# 4. Check all organizations
curl http://localhost:8080/api/v1/auth/organizations

# 5. Get server health
curl http://localhost:8080/actuator/health | jq .

# 6. View metrics
curl http://localhost:8080/actuator/metrics | jq .

# 7. Trigger sync with authentication
curl -X POST http://localhost:8080/api/v1/auth/ldap/sync/acme.com \
  -H "Authorization: Bearer YOUR_JWT_TOKEN_HERE"

# 8. Test with advanced LDAP filters (Active Directory)
curl -X POST http://localhost:8080/api/v1/auth/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "ad.company.com",
    "name": "Active Directory Domain",
    "ldapUrl": "ldap://dc.company.com:389",
    "ldapBaseDn": "dc=company,dc=com",
    "ldapBindDn": "CN=ServiceAccount,CN=Users,DC=company,DC=com",
    "ldapBindPassword": "ServiceAccountPassword123!",
    "userSearchFilter": "(&(objectClass=user)(objectCategory=person)(sAMAccountName={0}))",
    "emailAttribute": "userPrincipalName",
    "fullNameAttribute": "displayName"
  }'

# 9. Test with OpenLDAP filters
curl -X POST http://localhost:8080/api/v1/auth/organizations \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "openldap.org",
    "name": "OpenLDAP Server",
    "ldapUrl": "ldap://ldap.openldap.org:389",
    "ldapBaseDn": "dc=openldap,dc=org",
    "ldapBindDn": "cn=admin,dc=openldap,dc=org",
    "ldapBindPassword": "AdminPassword123!",
    "userSearchFilter": "(&(objectClass=inetOrgPerson)(uid={0}))",
    "emailAttribute": "mail",
    "fullNameAttribute": "cn"
  }'

EOF

echo ""
echo -e "${GREEN}✓ Testing complete!${NC}"

#!/bin/bash
# LDAP Authentication API Test Examples
# Use these curl commands to test the LDAP integration

BASE_URL="http://localhost:8080/api/v1/auth"

echo "=== 1. Create Organization ==="
curl -X POST "$BASE_URL/organizations" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "example.com",
    "name": "Example Corporation",
    "description": "Example Corp LDAP Integration",
    "ldapUrl": "ldap://ldap.example.com:389",
    "ldapBaseDn": "dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "admin_password",
    "ldapEnabled": true
  }'

echo -e "\n=== 2. Get Organization by Domain ==="
curl -X GET "$BASE_URL/organizations/example.com"

echo -e "\n=== 3. LDAP Login ==="
curl -X POST "$BASE_URL/ldap/login" \
  -H "Content-Type: application/json" \
  -d '{
    "domain": "example.com",
    "username": "jdoe",
    "password": "user_password"
  }'

echo -e "\n=== 4. Update Organization LDAP Settings ==="
# First, get the org ID from the create response
ORG_ID="550e8400-e29b-41d4-a716-446655440000"

curl -X PUT "$BASE_URL/organizations/$ORG_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "ldapUrl": "ldap://ldap2.example.com:389",
    "ldapBaseDn": "ou=users,dc=example,dc=com",
    "ldapBindDn": "cn=admin,dc=example,dc=com",
    "ldapBindPassword": "new_admin_password"
  }'

echo -e "\n=== 5. Regular Login (after LDAP user is synced) ==="
curl -X POST "$BASE_URL/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "jdoe@example.com",
    "password": "password"
  }'

echo -e "\n"

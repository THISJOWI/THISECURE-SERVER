#!/bin/bash
# Script para configurar usuarios de prueba en OpenLDAP
# Ejecutar después de levantar el contenedor: ./setup-ldap-test-users.sh

LDAP_HOST="localhost"
LDAP_PORT="389"
LDAP_ADMIN="cn=admin,dc=thisjowi,dc=local"
LDAP_PASSWORD="admin123"
BASE_DN="dc=thisjowi,dc=local"

echo "=== Configurando LDAP de prueba ==="

# Esperar a que LDAP esté listo
echo "Esperando a que LDAP esté disponible..."
sleep 5

# Crear OU para usuarios
cat << EOF | ldapadd -x -H ldap://$LDAP_HOST:$LDAP_PORT -D "$LDAP_ADMIN" -w "$LDAP_PASSWORD"
dn: ou=users,$BASE_DN
objectClass: organizationalUnit
ou: users

dn: ou=groups,$BASE_DN
objectClass: organizationalUnit
ou: groups
EOF

echo "✓ Organizational Units creadas"

# Crear usuarios de prueba
cat << EOF | ldapadd -x -H ldap://$LDAP_HOST:$LDAP_PORT -D "$LDAP_ADMIN" -w "$LDAP_PASSWORD"
# Usuario 1: Juan Pérez
dn: uid=jperez,ou=users,$BASE_DN
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: jperez
cn: Juan Pérez
sn: Pérez
givenName: Juan
mail: jperez@thisjowi.local
userPassword: password123
uidNumber: 1001
gidNumber: 1001
homeDirectory: /home/jperez

# Usuario 2: María García
dn: uid=mgarcia,ou=users,$BASE_DN
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: mgarcia
cn: María García
sn: García
givenName: María
mail: mgarcia@thisjowi.local
userPassword: password123
uidNumber: 1002
gidNumber: 1001
homeDirectory: /home/mgarcia

# Usuario 3: Carlos López
dn: uid=clopez,ou=users,$BASE_DN
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: clopez
cn: Carlos López
sn: López
givenName: Carlos
mail: clopez@thisjowi.local
userPassword: password123
uidNumber: 1003
gidNumber: 1001
homeDirectory: /home/clopez

# Usuario Admin Test
dn: uid=admin.test,ou=users,$BASE_DN
objectClass: inetOrgPerson
objectClass: posixAccount
objectClass: shadowAccount
uid: admin.test
cn: Admin Test
sn: Test
givenName: Admin
mail: admin@thisjowi.local
userPassword: admin123
uidNumber: 1000
gidNumber: 1000
homeDirectory: /home/admin
EOF

echo "✓ Usuarios de prueba creados"

# Mostrar usuarios creados
echo ""
echo "=== Usuarios disponibles ==="
echo "| Username    | Password    | Email                    |"
echo "|-------------|-------------|--------------------------|"
echo "| jperez      | password123 | jperez@thisjowi.local    |"
echo "| mgarcia     | password123 | mgarcia@thisjowi.local   |"
echo "| clopez      | password123 | clopez@thisjowi.local    |"
echo "| admin.test  | admin123    | admin@thisjowi.local     |"
echo ""
echo "=== Configuración para tu app ==="
echo "LDAP URL:       ldap://localhost:389"
echo "Base DN:        dc=thisjowi,dc=local"
echo "Bind DN:        cn=admin,dc=thisjowi,dc=local"
echo "Bind Password:  admin123"
echo "User Filter:    (&(objectClass=inetOrgPerson)(uid={0}))"
echo ""
echo "PHPLdapAdmin:   http://localhost:8088"
echo "  Login DN:     cn=admin,dc=thisjowi,dc=local"
echo "  Password:     admin123"

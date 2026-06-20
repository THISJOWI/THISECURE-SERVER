db.ldap_users.createIndex({ userId: 1 }, { unique: true });
db.ldap_users.createIndex({ domain: 1 });
db.ldap_users.createIndex({ orgId: 1 });

db.conversations.createIndex({ 'participants.userId': 1 });
db.conversations.createIndex({ updatedAt: -1 });

db.messages.createIndex({ conversationId: 1, sentAt: -1 });
db.messages.createIndex({ senderId: 1 });

"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.CassandraService = void 0;
const common_1 = require("@nestjs/common");
const config_1 = require("@nestjs/config");
const cassandra_driver_1 = require("cassandra-driver");
let CassandraService = class CassandraService {
    constructor(configService) {
        this.configService = configService;
        this.client = new cassandra_driver_1.Client({
            contactPoints: [this.configService.get('CASSANDRA_HOST', '127.0.0.1')],
            localDataCenter: this.configService.get('CASSANDRA_DATACENTER', 'datacenter1'),
            protocolOptions: {
                port: this.configService.get('CASSANDRA_PORT', 9042),
            },
            authProvider: new cassandra_driver_1.auth.PlainTextAuthProvider(this.configService.get('CASSANDRA_USERNAME', 'cassandra'), this.configService.get('CASSANDRA_PASSWORD', 'cassandra')),
        });
    }
    async onModuleInit() {
        try {
            console.log('ℹ️  Connecting to Cassandra...');
            await this.client.connect();
            console.log('✅ Connected to Cassandra');
            const keyspace = this.configService.get('CASSANDRA_KEYSPACE', 'messaging');
            // Create Keyspace
            await this.client.execute(`
        CREATE KEYSPACE IF NOT EXISTS ${keyspace} 
        WITH REPLICATION = { 
          'class' : 'SimpleStrategy', 
          'replication_factor' : 1 
        }
      `);
            console.log(`✅ Keyspace '${keyspace}' ready`);
            // Switch to Keyspace
            await this.client.execute(`USE ${keyspace}`);
            this.mapper = new cassandra_driver_1.mapping.Mapper(this.client, {
                models: {
                    'Message': {
                        tables: ['messages'],
                        mappings: new cassandra_driver_1.mapping.UnderscoreCqlToCamelCaseMappings()
                    }
                }
            });
            console.log('✅ Mapper initialized');
            await this.createTableIfNotExists();
        }
        catch (error) {
            console.error('❌ Error initializing Cassandra:', error.message);
            console.error('Full error:', error);
            // Don't rethrow - let service continue with null mapper
        }
    }
    async createTableIfNotExists() {
        try {
            // Drop old table if schema changed (conversation_id was uuid, now text)
            try {
                await this.client.execute('DROP TABLE IF EXISTS messages');
                console.log('ℹ️  Dropped old messages table for schema update');
            }
            catch (err) {
                console.log('ℹ️  Table drop skipped:', (err === null || err === void 0 ? void 0 : err.message) || err);
            }
            // Create table with all required columns (conversation_id as TEXT to support user IDs)
            const query = `
        CREATE TABLE IF NOT EXISTS messages (
            id uuid PRIMARY KEY,
            conversation_id text,
            sender_id text,
            recipient_id text,
            content text,
            sender text,
            timestamp timestamp,
            is_read boolean,
            created_at timestamp
        )`;
            await this.client.execute(query);
            console.log('✅ Messages table created with all columns');
            // Create index for conversation_id to enable efficient queries
            try {
                await this.client.execute(`
          CREATE INDEX IF NOT EXISTS idx_messages_conversation_id 
          ON messages (conversation_id)
        `);
                console.log('✅ Index created on conversation_id');
            }
            catch (err) {
                console.log('ℹ️  Index creation:', (err === null || err === void 0 ? void 0 : err.message) || err);
            }
        }
        catch (error) {
            console.error('❌ Error creating table:', error.message);
        }
    }
};
CassandraService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [config_1.ConfigService])
], CassandraService);
exports.CassandraService = CassandraService;

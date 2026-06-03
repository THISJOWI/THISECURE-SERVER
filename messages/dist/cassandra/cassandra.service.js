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
            queryOptions: { consistency: 1 },
            socketOptions: {
                connectTimeout: 30000,
                readTimeout: 30000,
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
            // Give Cassandra a moment to reach schema agreement
            await new Promise(resolve => setTimeout(resolve, 1000));
            // Switch to Keyspace
            await this.client.execute(`USE ${keyspace}`);
            // CREATE TABLE FIRST
            await this.createTableIfNotExists(keyspace);
            // Wait for table schema propagation
            await new Promise(resolve => setTimeout(resolve, 2000));
            // NOW initialize mapper
            this.mapper = new cassandra_driver_1.mapping.Mapper(this.client, {
                models: {
                    'Message': {
                        tables: ['messages'],
                        keyspace: keyspace,
                        mappings: new cassandra_driver_1.mapping.UnderscoreCqlToCamelCaseMappings()
                    }
                }
            });
            console.log('✅ Mapper initialized');
        }
        catch (error) {
            const errorMessage = error instanceof Error ? error.message : String(error);
            console.error('❌ Error initializing Cassandra:', errorMessage);
            console.error('Full error:', error);
        }
    }
    async createTableIfNotExists(keyspace) {
        var _a, _b;
        try {
            // Create table with all required columns (explicit keyspace)
            const query = `
        CREATE TABLE IF NOT EXISTS ${keyspace}.messages (
            id uuid PRIMARY KEY,
            conversation_id text,
            sender_id text,
            recipient_id text,
            content text,
            sender text,
            timestamp timestamp,
            is_read boolean,
            is_encrypted boolean,
            ephemeral_public_key text,
            created_at timestamp
        )`;
            await this.client.execute(query);
            console.log(`✅ Table '${keyspace}.messages' ready`);
            // Ensure new columns exist for E2EE (for existing tables)
            const alterQueries = [
                `ALTER TABLE ${keyspace}.messages ADD is_encrypted boolean`,
                `ALTER TABLE ${keyspace}.messages ADD ephemeral_public_key text`
            ];
            for (const q of alterQueries) {
                try {
                    await this.client.execute(q);
                    console.log(`✅ Altered table: ${q}`);
                }
                catch (e) {
                    // Ignore if column already exists (Invalid query usually)
                    if (!((_a = e.message) === null || _a === void 0 ? void 0 : _a.includes('already exists')) && !((_b = e.message) === null || _b === void 0 ? void 0 : _b.includes('Duplicate column name'))) {
                        console.log(`ℹ️  Note on alter table: ${e.message}`);
                    }
                }
            }
            // Create index for conversation_id
            try {
                await this.client.execute(`
          CREATE INDEX IF NOT EXISTS idx_messages_conversation_id 
          ON ${keyspace}.messages (conversation_id)
        `);
                console.log('✅ Index created on conversation_id');
            }
            catch (err) {
                console.log('ℹ️  Index creation:', (err === null || err === void 0 ? void 0 : err.message) || err);
            }
        }
        catch (error) {
            const errorMessage = error instanceof Error ? error.message : String(error);
            console.error('❌ Error creating table:', errorMessage);
        }
    }
};
CassandraService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [config_1.ConfigService])
], CassandraService);
exports.CassandraService = CassandraService;

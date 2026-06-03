import { Injectable, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Client, mapping, auth } from 'cassandra-driver';

@Injectable()
export class CassandraService implements OnModuleInit {
  client: Client;
  mapper: mapping.Mapper;

  constructor(private configService: ConfigService) {
    this.client = new Client({
      contactPoints: [this.configService.get<string>('CASSANDRA_HOST', '127.0.0.1')],
      localDataCenter: this.configService.get<string>('CASSANDRA_DATACENTER', 'datacenter1'),
      protocolOptions: {
        port: this.configService.get<number>('CASSANDRA_PORT', 9042),
      },
      queryOptions: { consistency: 1 },
      socketOptions: {
        connectTimeout: 30000,
        readTimeout: 30000,
      },
      authProvider: new auth.PlainTextAuthProvider(
        this.configService.get<string>('CASSANDRA_USERNAME', 'cassandra'),
        this.configService.get<string>('CASSANDRA_PASSWORD', 'cassandra'),
      ),
    });
  }

  async onModuleInit() {
    try {
      console.log('ℹ️  Connecting to Cassandra...');
      await this.client.connect();
      console.log('✅ Connected to Cassandra');

      const keyspace = this.configService.get<string>('CASSANDRA_KEYSPACE', 'messaging');

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
      this.mapper = new mapping.Mapper(this.client, {
        models: {
          'Message': {
            tables: ['messages'],
            keyspace: keyspace,
            mappings: new mapping.UnderscoreCqlToCamelCaseMappings()
          }
        }
      });
      console.log('✅ Mapper initialized');

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      console.error('❌ Error initializing Cassandra:', errorMessage);
      console.error('Full error:', error);
    }
  }

  private async createTableIfNotExists(keyspace: string) {
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
        } catch (e: any) {
          // Ignore if column already exists (Invalid query usually)
          if (!e.message?.includes('already exists') && !e.message?.includes('Duplicate column name')) {
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
      } catch (err: any) {
        console.log('ℹ️  Index creation:', err?.message || err);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      console.error('❌ Error creating table:', errorMessage);
    }
  }
}

import { Injectable, Logger, OnModuleInit, OnModuleDestroy, Inject } from '@nestjs/common';
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { HmacService } from '../kafka/hmac';
import { LdapService, LdapUserEvent } from './ldap.service';

@Injectable()
export class LdapConsumerService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(LdapConsumerService.name);
  private consumer: Consumer;

  constructor(
    @Inject('KAFKA_CLIENT') private readonly kafka: Kafka,
    private readonly hmac: HmacService,
    private readonly ldapService: LdapService,
  ) {}

  async onModuleInit(): Promise<void> {
    this.consumer = this.kafka.consumer({ groupId: 'messaging-ldap-group' });
    await this.consumer.connect();
    await this.consumer.subscribe({ topic: 'ldap-user-events', fromBeginning: true });

    await this.consumer.run({
      eachMessage: async (payload: EachMessagePayload) => {
        try {
          const rawValue = payload.message.value?.toString();
          if (!rawValue) return;

          const signature =
            payload.message.headers?.['X-Signature']?.toString() ||
            payload.message.headers?.['X-HMAC-Signature']?.toString();
          if (signature && !this.hmac.verify(rawValue, signature)) {
            this.logger.warn('HMAC verification failed, skipping message');
            return;
          }

          const event: LdapUserEvent = JSON.parse(rawValue);
          this.logger.log(`Received LDAP event: ${event.action} for user ${event.userId}`);

          switch (event.action) {
            case 'LDAP_USER_PROVISIONED':
              await this.ldapService.upsertUser(event);
              break;
            case 'LDAP_USER_DELETED':
              await this.ldapService.removeUser(event.userId);
              break;
            case 'LDAP_CONFIG_CHANGED':
              await this.ldapService.clearDomain(event.domain);
              break;
          }
        } catch (e) {
          this.logger.error('Failed to process LDAP event', e);
        }
      },
    });

    this.logger.log('LDAP consumer connected and listening to ldap-user-events');
  }

  async onModuleDestroy(): Promise<void> {
    await this.consumer?.disconnect();
  }
}

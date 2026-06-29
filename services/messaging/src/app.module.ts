import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { KafkaModule } from './kafka/kafka.module';
import { LdapModule } from './ldap/ldap.module';
import { GatewayModule } from './gateway/gateway.module';
import { ChatModule } from './chat/chat.module';
import { MetricsModule } from './metrics/metrics.module';

@Module({
  imports: [
    MongooseModule.forRoot(process.env.MONGODB_URI || 'mongodb://localhost:27017/messaging'),
    KafkaModule,
    LdapModule,
    GatewayModule,
    ChatModule,
    MetricsModule,
  ],
})
export class AppModule {}

import { Module, Global } from '@nestjs/common';
import { Kafka } from 'kafkajs';
import { HmacService } from './hmac';

const kafkaProvider = {
  provide: 'KAFKA_CLIENT',
  useFactory: () => {
    const brokers = [`${process.env.KAFKA_HOST || 'localhost'}:${process.env.KAFKA_PORT || '9092'}`];
    return new Kafka({
      clientId: 'messaging-service',
      brokers,
    });
  },
};

@Global()
@Module({
  providers: [kafkaProvider, HmacService],
  exports: ['KAFKA_CLIENT', HmacService],
})
export class KafkaModule {}

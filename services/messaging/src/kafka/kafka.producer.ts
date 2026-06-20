import { Injectable, Inject } from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { HmacService } from './hmac';

@Injectable()
export class KafkaProducerService {
  private producer: Producer;

  constructor(
    @Inject('KAFKA_CLIENT') private readonly kafka: Kafka,
    private readonly hmac: HmacService,
  ) {}

  async connect(): Promise<void> {
    this.producer = this.kafka.producer();
    await this.producer.connect();
  }

  async disconnect(): Promise<void> {
    await this.producer?.disconnect();
  }

  async publish(topic: string, key: string, message: Record<string, any>): Promise<void> {
    const value = JSON.stringify(message);
    const signature = this.hmac.sign(value);

    await this.producer.send({
      topic,
      messages: [
        {
          key,
          value,
          headers: {
            'X-Signature': signature,
          },
        },
      ],
    });
  }
}

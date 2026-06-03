import { NestFactory } from '@nestjs/core';
import { Transport, MicroserviceOptions } from '@nestjs/microservices';
import { SwaggerModule, DocumentBuilder } from '@nestjs/swagger';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);

  // Swagger / OpenAPI configuration
  const config = new DocumentBuilder()
    .setTitle('THISJOWI Messages Service API')
    .setDescription(
      'Messages microservice — provides real-time messaging with conversations, Cassandra storage, Kafka event consumption, and LDAP user discovery.',
    )
    .setVersion('1.0.0')
    .setContact('THISJOWI', 'https://thisjowi.uk', '')
    .setLicense('MIT', 'https://opensource.org/licenses/MIT')
    .addBearerAuth(
      { type: 'http', scheme: 'bearer', bearerFormat: 'JWT', description: 'Enter your JWT token' },
      'Bearer Authentication',
    )
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('api-docs', app, document);

  app.connectMicroservice<MicroserviceOptions>({
    transport: Transport.KAFKA,
    options: {
      client: {
        brokers: [`${process.env.KAFKA_HOST || 'localhost'}:${process.env.KAFKA_PORT || 9092}`],
      },
      consumer: {
        groupId: process.env.KAFKA_GROUP_ID || 'messages-service',
      },
    },
  });

  await app.startAllMicroservices();
  await app.listen(process.env.SERVER_PORT || 3000);
  console.log(`📚 Swagger docs available at: http://localhost:${process.env.SERVER_PORT || 3000}/api-docs`);
}
bootstrap();
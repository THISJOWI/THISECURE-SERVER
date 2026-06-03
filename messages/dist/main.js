"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@nestjs/core");
const microservices_1 = require("@nestjs/microservices");
const swagger_1 = require("@nestjs/swagger");
const app_module_1 = require("./app.module");
async function bootstrap() {
    const app = await core_1.NestFactory.create(app_module_1.AppModule);
    // Swagger / OpenAPI configuration
    const config = new swagger_1.DocumentBuilder()
        .setTitle('THISJOWI Messages Service API')
        .setDescription('Messages microservice — provides real-time messaging with conversations, Cassandra storage, Kafka event consumption, and LDAP user discovery.')
        .setVersion('1.0.0')
        .setContact('THISJOWI', 'https://thisjowi.uk', '')
        .setLicense('MIT', 'https://opensource.org/licenses/MIT')
        .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT', description: 'Enter your JWT token' }, 'Bearer Authentication')
        .build();
    const document = swagger_1.SwaggerModule.createDocument(app, config);
    swagger_1.SwaggerModule.setup('api-docs', app, document);
    app.connectMicroservice({
        transport: microservices_1.Transport.KAFKA,
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

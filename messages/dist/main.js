"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@nestjs/core");
const microservices_1 = require("@nestjs/microservices");
const app_module_1 = require("./app.module");
async function bootstrap() {
    const app = await core_1.NestFactory.create(app_module_1.AppModule);
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
}
bootstrap();

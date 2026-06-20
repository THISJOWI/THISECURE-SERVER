import { Module } from '@nestjs/common';
import { MessagingGateway } from './gateway.gateway';
import { GatewayService } from './gateway.service';
import { WsGuard } from './gateway.guard';

@Module({
  providers: [MessagingGateway, GatewayService, WsGuard],
  exports: [MessagingGateway, GatewayService, WsGuard],
})
export class GatewayModule {}

import {
  WebSocketGateway,
  WebSocketServer,
  OnGatewayConnection,
  OnGatewayDisconnect,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { Logger } from '@nestjs/common';
import { GatewayService } from './gateway.service';

@WebSocketGateway({
  cors: {
    origin: (process.env.CORS_ALLOWED_ORIGINS || process.env.FRONTEND_URL || '*').split(',').map(o => o.trim()),
    credentials: true,
  },
})
export class MessagingGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server: Server;

  private readonly logger = new Logger(MessagingGateway.name);

  constructor(private readonly gatewayService: GatewayService) {}

  handleConnection(socket: Socket): void {
    try {
      const auth = socket.handshake?.auth || {};
      const client = this.gatewayService.registerConnection(socket.id, auth);
      this.logger.log(`Client connected: ${client.userId} (socket: ${socket.id})`);

      socket.join(`user:${client.userId}`);
      socket.emit('connected', { userId: client.userId });
    } catch (error) {
      this.logger.warn(`Connection rejected: ${error.message}`);
      socket.emit('error', { message: 'Authentication failed' });
      socket.disconnect(true);
    }
  }

  handleDisconnect(socket: Socket): void {
    const userId = this.gatewayService.getUserId(socket.id);
    this.gatewayService.removeConnection(socket.id);
    this.logger.log(`Client disconnected: ${userId || 'unknown'} (socket: ${socket.id})`);
  }
}

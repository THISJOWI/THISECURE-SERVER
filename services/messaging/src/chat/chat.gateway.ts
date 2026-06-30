import {
  SubscribeMessage,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { Logger, UseGuards } from '@nestjs/common';
import { ChatService } from './chat.service';
import { GatewayService } from '../gateway/gateway.service';

@WebSocketGateway()
export class ChatGateway {
  @WebSocketServer()
  server: Server;

  private readonly logger = new Logger(ChatGateway.name);

  constructor(
    private readonly chatService: ChatService,
    private readonly gatewayService: GatewayService,
  ) {}

  @SubscribeMessage('sendMessage')
  async handleSendMessage(socket: Socket, payload: { conversationId: string; text: string; replyTo?: string; ephemeralPublicKey?: string }) {
    const userId = this.gatewayService.getUserId(socket.id);
    if (!userId) {
      socket.emit('error', { message: 'Not authenticated' });
      return;
    }

    try {
      const message = await this.chatService.sendMessage(
        userId,
        payload.conversationId,
        payload.text,
        payload.replyTo,
        payload.ephemeralPublicKey,
        this.server,
      );

      socket.emit('messageSent', message);
    } catch (error) {
      this.logger.error(`sendMessage failed: ${error.message}`);
      socket.emit('error', { message: error.message });
    }
  }

  @SubscribeMessage('markRead')
  async handleMarkRead(socket: Socket, payload: { conversationId: string }) {
    const userId = this.gatewayService.getUserId(socket.id);
    if (!userId) return;

    try {
      const senderIds = await this.chatService.markRead(userId, payload.conversationId);

      // Notify affected senders that their messages were read
      for (const senderId of senderIds) {
        for (const sid of this.gatewayService.getUserSockets(senderId)) {
          this.server.to(sid).emit('readUpdated', {
            conversationId: payload.conversationId,
            userId,
          });
        }
      }
    } catch (error) {
      this.logger.error(`markRead failed: ${error.message}`);
    }
  }

  @SubscribeMessage('typing')
  handleTyping(socket: Socket, payload: { conversationId: string }) {
    const userId = this.gatewayService.getUserId(socket.id);
    if (!userId) return;

    socket.broadcast.emit('userTyping', {
      conversationId: payload.conversationId,
      userId,
    });
  }

  @SubscribeMessage('stopTyping')
  handleStopTyping(socket: Socket, payload: { conversationId: string }) {
    const userId = this.gatewayService.getUserId(socket.id);
    if (!userId) return;

    socket.broadcast.emit('userStoppedTyping', {
      conversationId: payload.conversationId,
      userId,
    });
  }
}

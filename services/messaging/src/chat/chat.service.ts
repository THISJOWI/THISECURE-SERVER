import { Injectable, Logger, NotFoundException, ForbiddenException } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Conversation } from './schemas/conversation.schema';
import { Message } from './schemas/message.schema';
import { LdapService } from '../ldap/ldap.service';
import { GatewayService } from '../gateway/gateway.service';
import { Server } from 'socket.io';

@Injectable()
export class ChatService {
  private readonly logger = new Logger(ChatService.name);

  constructor(
    @InjectModel(Conversation.name) private readonly conversationModel: Model<Conversation>,
    @InjectModel(Message.name) private readonly messageModel: Model<Message>,
    private readonly ldapService: LdapService,
    private readonly gatewayService: GatewayService,
  ) {}

  async createConversation(
    userId: string,
    type: 'direct' | 'group',
    participantIds: string[],
    name?: string,
    server?: Server,
  ): Promise<Conversation> {
    const allParticipants = [...new Set([userId, ...participantIds])];

    if (type === 'direct') {
      const existing = await this.conversationModel.findOne({
        type: 'direct',
        'participants.userId': { $all: allParticipants },
        'participants': { $size: allParticipants.length },
      });
      if (existing) return existing;
    }

    const conversation = new this.conversationModel({
      type,
      name,
      participants: allParticipants.map(pid => ({ userId: pid, joinedAt: new Date() })),
    });
    await conversation.save();

    this.logger.log(`Conversation ${conversation._id} created (${type})`);

    for (const pid of allParticipants) {
      this.gatewayService.getUserSockets(pid).forEach(socketId => {
        server?.to(socketId).emit('conversationCreated', conversation);
      });
    }

    return conversation;
  }

  async getUserConversations(userId: string): Promise<any[]> {
    const conversations = await this.conversationModel
      .find({ 'participants.userId': userId })
      .sort({ updatedAt: -1 })
      .lean();

    return Promise.all(
      conversations.map(async (conv) => {
        const unreadCount = await this.messageModel.countDocuments({
          conversationId: conv._id,
          senderId: { $ne: userId },
          'readBy.userId': { $ne: userId },
        });

        return {
          ...conv,
          unreadCount,
        };
      }),
    );
  }

  async getMessages(conversationId: string, userId: string, page = 1, limit = 50) {
    const conversation = await this.conversationModel.findById(conversationId);
    if (!conversation) throw new NotFoundException('Conversation not found');
    if (!conversation.participants.some(p => p.userId === userId)) {
      throw new ForbiddenException('Not a participant');
    }

    const messages = await this.messageModel
      .find({ conversationId: new Types.ObjectId(conversationId) })
      .sort({ sentAt: -1 })
      .skip((page - 1) * limit)
      .limit(limit)
      .lean();

    return messages.map(msg => this.toMessageResponse(msg));
  }

  async sendMessage(
    userId: string,
    conversationId: string,
    text: string,
    replyTo?: string,
    server?: Server,
  ): Promise<Message> {
    const conversation = await this.conversationModel.findById(conversationId);
    if (!conversation) throw new NotFoundException('Conversation not found');
    if (!conversation.participants.some(p => p.userId === userId)) {
      throw new ForbiddenException('Not a participant');
    }

    const message = new this.messageModel({
      conversationId: new Types.ObjectId(conversationId),
      senderId: userId,
      text,
      replyTo: replyTo ? new Types.ObjectId(replyTo) : undefined,
      sentAt: new Date(),
      readBy: [{ userId, readAt: new Date() }],
    });
    await message.save();

    conversation.lastMessage = { text, senderId: userId, sentAt: message.sentAt };
    conversation.updatedAt = new Date();
    await conversation.save();

    for (const participant of conversation.participants) {
      if (participant.userId === userId) continue;
      this.gatewayService.getUserSockets(participant.userId).forEach(socketId => {
        server?.to(socketId).emit('newMessage', message.toObject());
      });
    }

    return this.toMessageResponse(message.toObject());
  }

  private toMessageResponse(msg: any): any {
    const senderId = msg.senderId?.toString();
    return {
      ...msg,
      id: (msg._id || msg.id)?.toString(),
      isRead: Array.isArray(msg.readBy) && msg.readBy.some((rb: any) => rb.userId?.toString() !== senderId),
    };
  }

  async markRead(userId: string, conversationId: string): Promise<string[]> {
    const conversation = await this.conversationModel.findById(conversationId);
    if (!conversation) throw new NotFoundException('Conversation not found');

    const affected = await this.messageModel.find(
      {
        conversationId: new Types.ObjectId(conversationId),
        senderId: { $ne: userId },
        'readBy.userId': { $ne: userId },
      },
      { senderId: 1, _id: 0 },
    ).lean();

    const senderIds = [...new Set(affected.map(m => m.senderId))];

    await this.messageModel.updateMany(
      {
        conversationId: new Types.ObjectId(conversationId),
        senderId: { $ne: userId },
        'readBy.userId': { $ne: userId },
      },
      {
        $push: { readBy: { userId, readAt: new Date() } },
      },
    );

    return senderIds;
  }
}

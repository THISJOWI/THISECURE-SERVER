import {
  Controller,
  Get,
  Post,
  Param,
  Query,
  Body,
  Req,
  UnauthorizedException,
} from '@nestjs/common';
import { ApiBearerAuth, ApiTags, ApiQuery } from '@nestjs/swagger';
import * as jwt from 'jsonwebtoken';
import { ChatService } from './chat.service';
import { CreateConversationDto } from './dto/create-conversation.dto';
import { MessagingGateway } from '../gateway/gateway.gateway';

@ApiTags('Chat')
@ApiBearerAuth()
@Controller('conversations')
export class ChatController {
  constructor(
    private readonly chatService: ChatService,
    private readonly gateway: MessagingGateway,
  ) {}

  private extractUserId(req: any): string {
    const auth = req.headers['authorization'] as string;
    if (!auth) throw new UnauthorizedException();
    const token = auth.replace('Bearer ', '');
    try {
      const decoded = jwt.verify(token, process.env.JWT_SECRET) as any;
      return decoded.sub || decoded.userId || decoded.id;
    } catch {
      throw new UnauthorizedException('Invalid or expired token');
    }
  }

  @Get()
  async getConversations(@Req() req: any) {
    const userId = this.extractUserId(req);
    return this.chatService.getUserConversations(userId);
  }

  @Post()
  async createConversation(@Req() req: any, @Body() dto: CreateConversationDto) {
    const userId = this.extractUserId(req);
    return this.chatService.createConversation(
      userId,
      dto.type,
      dto.participantIds,
      dto.name,
      this.gateway.server,
    );
  }

  @Get('between/:recipientId')
  async getConversationBetween(@Req() req: any, @Param('recipientId') recipientId: string) {
    const userId = this.extractUserId(req);
    const conversation = await this.chatService.createConversation(
      userId,
      'direct',
      [recipientId],
      undefined,
      this.gateway.server,
    );
    const messages = await this.chatService.getMessages(
      (conversation as any)._id.toString(),
      userId,
    );
    return {
      conversationId: (conversation as any)._id.toString(),
      messages,
    };
  }

  @Get(':id')
  @ApiQuery({ name: 'page', required: false })
  @ApiQuery({ name: 'limit', required: false })
  async getMessages(
    @Req() req: any,
    @Param('id') conversationId: string,
    @Query('page') page = 1,
    @Query('limit') limit = 50,
  ) {
    const userId = this.extractUserId(req);
    return this.chatService.getMessages(conversationId, userId, +page, +limit);
  }

  @Post(':id/messages')
  async sendMessage(
    @Req() req: any,
    @Param('id') conversationId: string,
    @Body() body: { text: string },
  ) {
    const userId = this.extractUserId(req);
    const message = await this.chatService.sendMessage(
      userId,
      conversationId,
      body.text,
      undefined,
      this.gateway.server,
    );
    return { success: true, data: message };
  }
}

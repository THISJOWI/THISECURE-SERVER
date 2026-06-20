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
}

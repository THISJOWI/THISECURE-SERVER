import { Controller, Get, Post, Body, Param, Query, Headers, Put, Delete, ForbiddenException, UnauthorizedException } from '@nestjs/common';
import { EventPattern, Payload } from '@nestjs/microservices';
import { ApiTags, ApiOperation, ApiBearerAuth, ApiHeader, ApiParam, ApiQuery } from '@nestjs/swagger';
import { MessagesService } from './messages.service';
import { CreateMessageDto } from './dto/create-message.dto';

interface JwtPayload {
  userId?: string;
  sub?: string;
  email?: string;
  accountType?: string;
  isLdapUser?: boolean;
}

@ApiTags('Messages')
@ApiBearerAuth('Bearer Authentication')
@Controller('/v1/messages')
export class MessagesController {
  private readonly AUTH_SERVICE_URL = process.env.AUTH_SERVICE_URL || 'http://auth/v1/auth';

  constructor(private readonly messagesService: MessagesService) {
    console.log(`🔐 Messages Service configured with AUTH_SERVICE_URL: ${this.AUTH_SERVICE_URL}`);
  }

  @Post()
  @ApiOperation({ summary: 'Create a new message' })
  async create(@Body() createMessageDto: CreateMessageDto, @Headers('authorization') authHeader: string) {
    const userId = await this.extractUserIdFromToken(authHeader);
    if (!userId) {
      throw new ForbiddenException('Message service is only available for Business accounts with LDAP');
    }

    // Ensure the senderId in the DTO matches the userId in the token
    if (createMessageDto.senderId !== userId) {
      createMessageDto.senderId = userId;
    }

    return this.messagesService.create(createMessageDto);
  }

  @Get()
  @ApiOperation({ summary: 'Get all messages for the authenticated user' })
  async findAll(@Headers('authorization') authHeader: string) {
    const userId = await this.extractUserIdFromToken(authHeader);
    if (!userId) {
      return [];
    }
    return this.messagesService.findByUserId(userId);
  }

  @Get('conversations')
  @ApiOperation({ summary: 'Get all conversations for the authenticated user' })
  async getConversations(@Headers('authorization') authHeader: string) {
    try {
      // Extract userId from JWT token if available
      const userId = await this.extractUserIdFromToken(authHeader);
      if (!userId) {
        console.warn('⚠️  No userId extracted from token');
        return { success: true, data: [] };
      }

      console.log(`ℹ️  Loading conversations for user: ${userId}`);
      const conversations = await this.messagesService.getConversationsForUser(userId);
      console.log(`✅ Found ${conversations.length} conversations`);
      return { success: true, data: conversations };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : '';
      console.error('❌ Error in getConversations:', errorMessage);
      console.error('Stack trace:', errorStack);
      // Return empty conversations instead of error to prevent UI crashes
      return {
        success: true,
        data: [],
        warning: `Error loading conversations: ${errorMessage}`
      };
    }
  }

  @Post('conversations')
  @ApiOperation({ summary: 'Create a new conversation' })
  async createConversation(
    @Body() body: { participantIds: string[] },
    @Headers('authorization') authHeader: string,
  ) {
    const userId = await this.extractUserIdFromToken(authHeader);
    if (!userId) {
      return { success: false, message: 'Unauthorized' };
    }
    return this.messagesService.createConversation(body.participantIds, userId);
  }

  @Get('between/:recipientId')
  @ApiOperation({ summary: 'Get messages between the authenticated user and a recipient' })
  @ApiParam({ name: 'recipientId', description: 'ID of the recipient user' })
  getMessagesBetweenUsers(
    @Param('recipientId') recipientId: string,
    @Headers('authorization') authHeader: string,
  ) {
    const userId = this.extractUserIdFromTokenSync(authHeader);
    if (!userId) {
      return [];
    }
    return this.messagesService.getMessagesBetweenUsers(userId, recipientId);
  }

  @Get(':conversationId')
  @ApiOperation({ summary: 'Get messages in a conversation' })
  @ApiParam({ name: 'conversationId', description: 'ID of the conversation' })
  async getMessages(
    @Param('conversationId') conversationId: string,
    @Headers('authorization') authHeader: string,
  ) {
    try {
      const userId = await this.extractUserIdFromToken(authHeader);
      if (!userId) {
        return { success: false, message: 'Unauthorized' };
      }
      const messages = await this.messagesService.getMessagesByConversation(conversationId, userId);
      return { success: true, data: messages };
    } catch (error) {
      console.error('❌ Error in getMessages:', error);
      return { success: false, data: [], message: 'Error loading messages' };
    }
  }

  @Put(':conversationId/read')
  @ApiOperation({ summary: 'Mark all messages in a conversation as read' })
  @ApiParam({ name: 'conversationId', description: 'ID of the conversation' })
  async markAsRead(
    @Param('conversationId') conversationId: string,
    @Headers('authorization') authHeader: string,
  ) {
    const userId = await this.extractUserIdFromToken(authHeader);
    if (!userId) return { success: false };
    return this.messagesService.markAsRead(conversationId, userId);
  }

  @Delete(':id')
  @ApiOperation({ summary: 'Delete a message' })
  @ApiParam({ name: 'id', description: 'ID of the message to delete' })
  @ApiQuery({ name: 'type', required: false, enum: ['me', 'everyone'], description: 'Deletion scope' })
  async deleteMessage(
    @Param('id') id: string,
    @Query('type') type: 'me' | 'everyone' = 'everyone',
    @Headers('authorization') authHeader: string,
  ) {
    const userId = await this.extractUserIdFromToken(authHeader);
    if (!userId) return { success: false };
    return this.messagesService.delete(id, userId, type);
  }

  @Get('ldap-users/:domain')

  async getLdapUsersByDomain(
    @Param('domain') domain: string,
    @Headers('authorization') authHeader: string,
  ) {
    try {
      console.log(`📱 Fetching LDAP users for domain: ${domain}`);
      console.log(`🌐 Using AUTH_SERVICE_URL: ${this.AUTH_SERVICE_URL}`);

      const userId = await this.extractUserIdFromToken(authHeader);
      if (!userId) {
        console.warn('⚠️ Unauthorized or non-Business/LDAP user');
        return {
          success: false,
          data: [],
          message: 'Message service is only available for Business accounts with LDAP',
        };
      }

      const url = `${this.AUTH_SERVICE_URL}/ldap/users/${domain}`;
      console.log(`📡 Fetching from: ${url}`);

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);

      let response;
      try {
        response = await fetch(url, {
          signal: controller.signal,
          headers: {
            'Content-Type': 'application/json',
            'Authorization': authHeader,
          }
        });
      } catch (fetchError) {
        clearTimeout(timeoutId);
        const errorMsg = fetchError instanceof Error ? fetchError.message : String(fetchError);
        console.error(`❌ Fetch error to ${url}:`, errorMsg);

        if (fetchError instanceof Error && fetchError.name === 'AbortError') {
          return {
            success: false,
            data: [],
            message: `Timeout connecting to auth service at ${url}`,
          };
        }

        return {
          success: false,
          data: [],
          message: `Failed to connect to auth service: ${errorMsg}`,
        };
      }

      clearTimeout(timeoutId);
      console.log(`📊 Response status: ${response.status}`);

      if (!response.ok) {
        const errorBody = await response.text().catch(() => '');
        console.error(`❌ HTTP error ${response.status}:`, errorBody);
        return {
          success: false,
          data: [],
          message: `Auth service returned HTTP ${response.status}`,
        };
      }

      const data = await response.json() as any;
      console.log(`✅ Found ${data.count} LDAP users for domain: ${domain}`);

      return {
        success: true,
        data: data.users || [],
        count: data.count || 0,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      console.error(`❌ Error fetching LDAP users for domain ${domain}:`, errorMessage);
      console.error(`Stack:`, error);
      return {
        success: false,
        data: [],
        message: `Failed to fetch LDAP users: ${errorMessage}`,
      };
    }
  }

  @EventPattern('auth-events')
  async handleUserRegistered(@Payload() message: any) {
    console.log('Received generic event from Auth:', message);
  }

  private async extractUserIdFromToken(authHeader: string): Promise<string | null> {
    return this.extractUserIdFromTokenSync(authHeader);
  }

  private extractUserIdFromTokenSync(authHeader: string): string | null {
    // Simple extraction - in production, properly validate JWT
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return null;
    }
    try {
      const token = authHeader.substring(7);
      // Decode JWT payload (without verification for simplicity)
      const parts = token.split('.');
      if (parts.length !== 3) return null;

      const payload: JwtPayload = JSON.parse(Buffer.from(parts[1], 'base64').toString());

      // LOGIC: Only Business account with LDAP enabled
      if (payload.accountType !== 'Business' || !payload.isLdapUser) {
        console.warn(`🛑 User ${payload.userId || payload.email} rejected: AccountType=${payload.accountType}, LDAP=${!!payload.isLdapUser}`);
        return null;
      }

      return payload.userId || payload.sub || null;
    } catch (e) {
      return null;
    }
  }
}

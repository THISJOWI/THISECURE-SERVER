"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MessagesController = void 0;
const common_1 = require("@nestjs/common");
const microservices_1 = require("@nestjs/microservices");
const swagger_1 = require("@nestjs/swagger");
const messages_service_1 = require("./messages.service");
const create_message_dto_1 = require("./dto/create-message.dto");
let MessagesController = class MessagesController {
    constructor(messagesService) {
        this.messagesService = messagesService;
        this.AUTH_SERVICE_URL = process.env.AUTH_SERVICE_URL || 'http://auth/v1/auth';
        console.log(`🔐 Messages Service configured with AUTH_SERVICE_URL: ${this.AUTH_SERVICE_URL}`);
    }
    async create(createMessageDto, authHeader) {
        const userId = await this.extractUserIdFromToken(authHeader);
        if (!userId) {
            throw new common_1.ForbiddenException('Message service is only available for Business accounts with LDAP');
        }
        // Ensure the senderId in the DTO matches the userId in the token
        if (createMessageDto.senderId !== userId) {
            createMessageDto.senderId = userId;
        }
        return this.messagesService.create(createMessageDto);
    }
    async findAll(authHeader) {
        const userId = await this.extractUserIdFromToken(authHeader);
        if (!userId) {
            return [];
        }
        return this.messagesService.findByUserId(userId);
    }
    async getConversations(authHeader) {
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
        }
        catch (error) {
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
    async createConversation(body, authHeader) {
        const userId = await this.extractUserIdFromToken(authHeader);
        if (!userId) {
            return { success: false, message: 'Unauthorized' };
        }
        return this.messagesService.createConversation(body.participantIds, userId);
    }
    getMessagesBetweenUsers(recipientId, authHeader) {
        const userId = this.extractUserIdFromTokenSync(authHeader);
        if (!userId) {
            return [];
        }
        return this.messagesService.getMessagesBetweenUsers(userId, recipientId);
    }
    async getMessages(conversationId, authHeader) {
        try {
            const userId = await this.extractUserIdFromToken(authHeader);
            if (!userId) {
                return { success: false, message: 'Unauthorized' };
            }
            const messages = await this.messagesService.getMessagesByConversation(conversationId, userId);
            return { success: true, data: messages };
        }
        catch (error) {
            console.error('❌ Error in getMessages:', error);
            return { success: false, data: [], message: 'Error loading messages' };
        }
    }
    async markAsRead(conversationId, authHeader) {
        const userId = await this.extractUserIdFromToken(authHeader);
        if (!userId)
            return { success: false };
        return this.messagesService.markAsRead(conversationId, userId);
    }
    async deleteMessage(id, type = 'everyone', authHeader) {
        const userId = await this.extractUserIdFromToken(authHeader);
        if (!userId)
            return { success: false };
        return this.messagesService.delete(id, userId, type);
    }
    async getLdapUsersByDomain(domain, authHeader) {
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
            }
            catch (fetchError) {
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
            const data = await response.json();
            console.log(`✅ Found ${data.count} LDAP users for domain: ${domain}`);
            return {
                success: true,
                data: data.users || [],
                count: data.count || 0,
            };
        }
        catch (error) {
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
    async handleUserRegistered(message) {
        console.log('Received generic event from Auth:', message);
    }
    async extractUserIdFromToken(authHeader) {
        return this.extractUserIdFromTokenSync(authHeader);
    }
    extractUserIdFromTokenSync(authHeader) {
        // Simple extraction - in production, properly validate JWT
        if (!authHeader || !authHeader.startsWith('Bearer ')) {
            return null;
        }
        try {
            const token = authHeader.substring(7);
            // Decode JWT payload (without verification for simplicity)
            const parts = token.split('.');
            if (parts.length !== 3)
                return null;
            const payload = JSON.parse(Buffer.from(parts[1], 'base64').toString());
            // LOGIC: Only Business account with LDAP enabled
            if (payload.accountType !== 'Business' || !payload.isLdapUser) {
                console.warn(`🛑 User ${payload.userId || payload.email} rejected: AccountType=${payload.accountType}, LDAP=${!!payload.isLdapUser}`);
                return null;
            }
            return payload.userId || payload.sub || null;
        }
        catch (e) {
            return null;
        }
    }
};
__decorate([
    (0, common_1.Post)(),
    (0, swagger_1.ApiOperation)({ summary: 'Create a new message' }),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_message_dto_1.CreateMessageDto, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "create", null);
__decorate([
    (0, common_1.Get)(),
    (0, swagger_1.ApiOperation)({ summary: 'Get all messages for the authenticated user' }),
    __param(0, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "findAll", null);
__decorate([
    (0, common_1.Get)('conversations'),
    (0, swagger_1.ApiOperation)({ summary: 'Get all conversations for the authenticated user' }),
    __param(0, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "getConversations", null);
__decorate([
    (0, common_1.Post)('conversations'),
    (0, swagger_1.ApiOperation)({ summary: 'Create a new conversation' }),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "createConversation", null);
__decorate([
    (0, common_1.Get)('between/:recipientId'),
    (0, swagger_1.ApiOperation)({ summary: 'Get messages between the authenticated user and a recipient' }),
    (0, swagger_1.ApiParam)({ name: 'recipientId', description: 'ID of the recipient user' }),
    __param(0, (0, common_1.Param)('recipientId')),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], MessagesController.prototype, "getMessagesBetweenUsers", null);
__decorate([
    (0, common_1.Get)(':conversationId'),
    (0, swagger_1.ApiOperation)({ summary: 'Get messages in a conversation' }),
    (0, swagger_1.ApiParam)({ name: 'conversationId', description: 'ID of the conversation' }),
    __param(0, (0, common_1.Param)('conversationId')),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "getMessages", null);
__decorate([
    (0, common_1.Put)(':conversationId/read'),
    (0, swagger_1.ApiOperation)({ summary: 'Mark all messages in a conversation as read' }),
    (0, swagger_1.ApiParam)({ name: 'conversationId', description: 'ID of the conversation' }),
    __param(0, (0, common_1.Param)('conversationId')),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "markAsRead", null);
__decorate([
    (0, common_1.Delete)(':id'),
    (0, swagger_1.ApiOperation)({ summary: 'Delete a message' }),
    (0, swagger_1.ApiParam)({ name: 'id', description: 'ID of the message to delete' }),
    (0, swagger_1.ApiQuery)({ name: 'type', required: false, enum: ['me', 'everyone'], description: 'Deletion scope' }),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Query)('type')),
    __param(2, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "deleteMessage", null);
__decorate([
    (0, common_1.Get)('ldap-users/:domain'),
    __param(0, (0, common_1.Param)('domain')),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "getLdapUsersByDomain", null);
__decorate([
    (0, microservices_1.EventPattern)('auth-events'),
    __param(0, (0, microservices_1.Payload)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "handleUserRegistered", null);
MessagesController = __decorate([
    (0, swagger_1.ApiTags)('Messages'),
    (0, swagger_1.ApiBearerAuth)('Bearer Authentication'),
    (0, common_1.Controller)('/v1/messages'),
    __metadata("design:paramtypes", [messages_service_1.MessagesService])
], MessagesController);
exports.MessagesController = MessagesController;

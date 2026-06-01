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
const messages_service_1 = require("./messages.service");
const create_message_dto_1 = require("./dto/create-message.dto");
let MessagesController = class MessagesController {
    constructor(messagesService) {
        this.messagesService = messagesService;
    }
    create(createMessageDto) {
        return this.messagesService.create(createMessageDto);
    }
    findAll(senderId, recipientId) {
        if (senderId && recipientId) {
            return this.messagesService.findByUsers(senderId, recipientId);
        }
        return this.messagesService.findAll();
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
            console.error('❌ Error in getConversations:', error.message);
            console.error('Stack trace:', error.stack);
            // Return empty conversations instead of error to prevent UI crashes
            return {
                success: true,
                data: [],
                warning: `Error loading conversations: ${error.message}`
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
    getMessages(conversationId) {
        return this.messagesService.getMessagesByConversation(conversationId);
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
            return payload.userId || payload.sub || null;
        }
        catch (e) {
            return null;
        }
    }
};
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_message_dto_1.CreateMessageDto]),
    __metadata("design:returntype", void 0)
], MessagesController.prototype, "create", null);
__decorate([
    (0, common_1.Get)(),
    __param(0, (0, common_1.Query)('senderId')),
    __param(1, (0, common_1.Query)('recipientId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], MessagesController.prototype, "findAll", null);
__decorate([
    (0, common_1.Get)('conversations'),
    __param(0, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "getConversations", null);
__decorate([
    (0, common_1.Post)('conversations'),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "createConversation", null);
__decorate([
    (0, common_1.Get)('between/:recipientId'),
    __param(0, (0, common_1.Param)('recipientId')),
    __param(1, (0, common_1.Headers)('authorization')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], MessagesController.prototype, "getMessagesBetweenUsers", null);
__decorate([
    (0, common_1.Get)(':conversationId'),
    __param(0, (0, common_1.Param)('conversationId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MessagesController.prototype, "getMessages", null);
__decorate([
    (0, microservices_1.EventPattern)('auth-events'),
    __param(0, (0, microservices_1.Payload)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", Promise)
], MessagesController.prototype, "handleUserRegistered", null);
MessagesController = __decorate([
    (0, common_1.Controller)('/api/v1/messages'),
    __metadata("design:paramtypes", [messages_service_1.MessagesService])
], MessagesController);
exports.MessagesController = MessagesController;

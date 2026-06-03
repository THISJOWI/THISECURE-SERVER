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
Object.defineProperty(exports, "__esModule", { value: true });
exports.MessagesService = void 0;
const common_1 = require("@nestjs/common");
const cassandra_service_1 = require("../cassandra/cassandra.service");
const uuid_1 = require("uuid");
let MessagesService = class MessagesService {
    constructor(cassandraService) {
        this.cassandraService = cassandraService;
    }
    get messageClient() {
        if (!this.cassandraService || !this.cassandraService.mapper) {
            return null;
        }
        return this.cassandraService.mapper.forModel('Message');
    }
    onModuleInit() {
        console.log('ℹ️  MessagesService initialized');
    }
    // Helper to convert Cassandra result to array
    async toArray(result) {
        if (!result)
            return [];
        if (Array.isArray(result))
            return result;
        // Cassandra mapper returns a Result object with toArray() method
        if (typeof result.toArray === 'function') {
            return result.toArray();
        }
        // If it's iterable, convert to array
        if (result[Symbol.iterator]) {
            return [...result];
        }
        return [];
    }
    async create(createMessageDto) {
        let conversationId = createMessageDto.conversationId;
        // If conversationId is 'new' or missing, we need to resolve it
        if (!conversationId || conversationId === 'new') {
            if (!createMessageDto.recipientId) {
                throw new Error('Recipient ID is required to start a new conversation');
            }
            // Look for existing conversation between these two users
            const existingConv = await this.createConversation([createMessageDto.senderId, createMessageDto.recipientId], createMessageDto.senderId);
            if (existingConv.success && existingConv.data) {
                conversationId = existingConv.data.id;
                console.log(`Resolved conversationId: ${conversationId}`);
            }
            else {
                conversationId = (0, uuid_1.v4)();
            }
        }
        const id = (0, uuid_1.v4)();
        const message = {
            id,
            conversationId: conversationId,
            senderId: createMessageDto.senderId,
            recipientId: createMessageDto.recipientId,
            content: createMessageDto.content,
            sender: createMessageDto.sender,
            timestamp: new Date(),
            isRead: false,
            isEncrypted: createMessageDto.isEncrypted || false,
            ephemeralPublicKey: createMessageDto.ephemeralPublicKey || null,
            createdAt: new Date(),
        };
        console.log('Creating message:', JSON.stringify(message, null, 2));
        if (!this.messageClient) {
            throw new Error('Database client not initialized');
        }
        try {
            await this.messageClient.insert(message);
            console.log('Message inserted successfully');
        }
        catch (error) {
            console.error('Error inserting message:', error);
            throw error;
        }
        // Return the created message
        return message;
    }
    async findByUserId(userId) {
        try {
            if (!this.messageClient) {
                return [];
            }
            const allMessages = await this.messageClient.findAll();
            const messagesArray = await this.toArray(allMessages);
            return messagesArray.filter((msg) => msg.senderId === userId || msg.recipientId === userId);
        }
        catch (error) {
            console.error('❌ Error in findByUserId:', error);
            return [];
        }
    }
    async findByUsers(senderId, recipientId) {
        const allMessages = await this.findByUserId(senderId);
        return allMessages.filter((msg) => (msg.senderId === senderId && msg.recipientId === recipientId) ||
            (msg.senderId === recipientId && msg.recipientId === senderId));
    }
    async getConversationsForUser(userId) {
        try {
            if (!userId) {
                console.warn('⚠️  No userId provided to getConversationsForUser');
                return [];
            }
            if (!this.messageClient) {
                console.warn('⚠️  Message client not initialized in getConversationsForUser');
                return [];
            }
            const allMessages = await this.findByUserId(userId);
            console.log(`ℹ️  Found ${allMessages.length} total messages for user ${userId}`);
            // Group by the OTHER user (not by conversationId) to avoid duplicates
            const conversations = new Map();
            allMessages.forEach((msg) => {
                const otherUserId = msg.senderId === userId ? msg.recipientId : msg.senderId;
                if (!otherUserId)
                    return;
                const convId = msg.conversationId || otherUserId;
                if (!conversations.has(convId)) {
                    conversations.set(convId, {
                        id: convId,
                        participants: [
                            { id: userId, fullName: null, email: null },
                            { id: otherUserId, fullName: null, email: null },
                        ],
                        lastMessage: {
                            id: msg.id,
                            conversationId: msg.conversationId,
                            senderId: msg.senderId,
                            content: msg.content,
                            timestamp: msg.timestamp,
                            isRead: msg.isRead,
                            isEncrypted: msg.isEncrypted,
                            ephemeralPublicKey: msg.ephemeralPublicKey,
                        },
                        unreadCount: (!msg.isRead && msg.recipientId === userId) ? 1 : 0,
                        updatedAt: msg.timestamp,
                    });
                }
                else {
                    const existing = conversations.get(convId);
                    if (new Date(msg.timestamp) > new Date(existing.updatedAt)) {
                        existing.lastMessage = {
                            id: msg.id,
                            conversationId: msg.conversationId,
                            senderId: msg.senderId,
                            content: msg.content,
                            timestamp: msg.timestamp,
                            isRead: msg.isRead,
                            isEncrypted: msg.isEncrypted,
                            ephemeralPublicKey: msg.ephemeralPublicKey,
                        };
                        existing.updatedAt = msg.timestamp;
                    }
                    if (!msg.isRead && msg.recipientId === userId) {
                        existing.unreadCount++;
                    }
                }
            });
            return Array.from(conversations.values());
        }
        catch (error) {
            console.error('❌ Error in getConversationsForUser:', error);
            throw error;
        }
    }
    async getMessagesByConversation(conversationId, userId) {
        const allMessages = await this.findByUserId(userId);
        return allMessages.filter((msg) => msg.conversationId === conversationId);
    }
    async getMessagesBetweenUsers(userId, recipientId) {
        const allMessages = await this.findByUserId(userId);
        return allMessages
            .filter((msg) => (msg.senderId === userId && msg.recipientId === recipientId) ||
            (msg.senderId === recipientId && msg.recipientId === userId))
            .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
    }
    async createConversation(participantIds, creatorId) {
        if (participantIds.length < 2) {
            return { success: false, message: 'At least two participants required' };
        }
        const allMessages = await this.findByUserId(creatorId);
        const sortedParticipants = [...participantIds].sort();
        for (const msg of allMessages) {
            const msgParticipants = [msg.senderId, msg.recipientId].sort();
            if (msgParticipants[0] === sortedParticipants[0] &&
                msgParticipants[1] === sortedParticipants[1]) {
                return {
                    success: true,
                    data: {
                        id: msg.conversationId,
                        participants: participantIds.map(id => ({
                            id: id,
                            fullName: null,
                            email: null,
                        })),
                        lastMessage: {
                            id: msg.id,
                            conversationId: msg.conversationId,
                            senderId: msg.senderId,
                            content: msg.content,
                            timestamp: msg.timestamp,
                            isRead: msg.isRead,
                        },
                        unreadCount: 0,
                        updatedAt: msg.timestamp,
                    },
                };
            }
        }
        const conversationId = (0, uuid_1.v4)();
        return {
            success: true,
            data: {
                id: conversationId,
                participants: participantIds.map(id => ({
                    id: id,
                    fullName: null,
                    email: null,
                })),
                lastMessage: null,
                unreadCount: 0,
                updatedAt: new Date().toISOString(),
            },
        };
    }
    async markAsRead(conversationId, userId) {
        try {
            const client = this.messageClient;
            if (!client)
                return { success: false, message: 'Client not ready' };
            const allMessages = await this.findByUserId(userId);
            const unreadMessages = allMessages.filter((msg) => msg.conversationId === conversationId && msg.recipientId === userId && !msg.isRead);
            for (const msg of unreadMessages) {
                await client.update({ id: msg.id, isRead: true });
            }
            return { success: true };
        }
        catch (error) {
            console.error('❌ Error marking as read:', error);
            return { success: false, message: error.message };
        }
    }
    async delete(id, userId, type = 'everyone') {
        try {
            const client = this.messageClient;
            if (!client)
                return { success: false, message: 'Client not ready' };
            const msg = await client.get({ id });
            if (msg && msg.senderId === userId) {
                await client.remove({ id });
                return { success: true };
            }
            return { success: false, message: 'Unauthorized or not found' };
        }
        catch (error) {
            console.error('❌ Error deleting message:', error);
            return { success: false, message: error.message };
        }
    }
};
MessagesService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [cassandra_service_1.CassandraService])
], MessagesService);
exports.MessagesService = MessagesService;

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
    onModuleInit() {
        try {
            if (!this.cassandraService || !this.cassandraService.mapper) {
                console.error('❌ CassandraService or mapper is not available');
                return;
            }
            this.messageClient = this.cassandraService.mapper.forModel('Message');
            console.log('✅ Message mapper initialized successfully');
        }
        catch (error) {
            console.error('❌ Error initializing message mapper:', error.message);
        }
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
        const id = (0, uuid_1.v4)();
        const message = {
            id,
            conversationId: createMessageDto.conversationId || (0, uuid_1.v4)(),
            senderId: createMessageDto.senderId,
            recipientId: createMessageDto.recipientId,
            content: createMessageDto.content,
            sender: createMessageDto.sender,
            timestamp: new Date(),
            isRead: false,
            createdAt: new Date(),
        };
        console.log('Creating message:', JSON.stringify(message, null, 2));
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
    async findAll() {
        try {
            if (!this.messageClient) {
                console.warn('⚠️  Message client not initialized, returning empty array');
                return [];
            }
            const result = await this.messageClient.findAll();
            return this.toArray(result);
        }
        catch (error) {
            console.error('❌ Error fetching all messages:', error.message);
            return [];
        }
    }
    async findByUsers(senderId, recipientId) {
        const allMessages = await this.findAll();
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
            const allMessages = await this.findAll();
            console.log(`ℹ️  Found ${allMessages.length} total messages`);
            // Group by the OTHER user (not by conversationId) to avoid duplicates
            const conversations = new Map();
            allMessages.forEach((msg) => {
                if (msg.senderId === userId || msg.recipientId === userId) {
                    // Use the OTHER user's ID as the key to group all messages with that person
                    const otherUserId = msg.senderId === userId ? msg.recipientId : msg.senderId;
                    if (!conversations.has(otherUserId)) {
                        conversations.set(otherUserId, {
                            id: otherUserId,
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
                            },
                            unreadCount: (!msg.isRead && msg.recipientId === userId) ? 1 : 0,
                            updatedAt: msg.timestamp,
                        });
                    }
                    else {
                        // Update last message if this one is newer
                        const existing = conversations.get(otherUserId);
                        if (new Date(msg.timestamp) > new Date(existing.updatedAt)) {
                            existing.lastMessage = {
                                id: msg.id,
                                conversationId: msg.conversationId,
                                senderId: msg.senderId,
                                content: msg.content,
                                timestamp: msg.timestamp,
                                isRead: msg.isRead,
                            };
                            existing.updatedAt = msg.timestamp;
                        }
                        if (!msg.isRead && msg.recipientId === userId) {
                            existing.unreadCount++;
                        }
                    }
                }
            });
            const result = Array.from(conversations.values());
            console.log(`ℹ️  Returning ${result.length} conversations for user ${userId}`);
            return result;
        }
        catch (error) {
            console.error('❌ Error in getConversationsForUser:', error.message);
            throw error;
        }
    }
    async getMessagesByConversation(conversationId) {
        const allMessages = await this.findAll();
        return allMessages.filter((msg) => msg.conversationId === conversationId);
    }
    async getMessagesBetweenUsers(userId, recipientId) {
        const allMessages = await this.findAll();
        return allMessages
            .filter((msg) => (msg.senderId === userId && msg.recipientId === recipientId) ||
            (msg.senderId === recipientId && msg.recipientId === userId))
            .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
    }
    async createConversation(participantIds, creatorId) {
        // Check if conversation already exists between these participants
        const allMessages = await this.findAll();
        const sortedParticipants = [...participantIds].sort();
        // Look for existing conversation between these users
        for (const msg of allMessages) {
            const msgParticipants = [msg.senderId, msg.recipientId].sort();
            if (msgParticipants[0] === sortedParticipants[0] &&
                msgParticipants[1] === sortedParticipants[1]) {
                // Found existing conversation
                console.log('Found existing conversation:', msg.conversationId);
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
        // No existing conversation found, create new one
        const conversationId = (0, uuid_1.v4)();
        console.log('Creating new conversation:', conversationId);
        // Create a conversation object
        const conversation = {
            id: conversationId,
            participants: participantIds.map(id => ({
                id: id,
                fullName: null,
                email: null,
            })),
            lastMessage: null,
            unreadCount: 0,
            updatedAt: new Date().toISOString(),
        };
        return {
            success: true,
            data: conversation,
        };
    }
};
MessagesService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [cassandra_service_1.CassandraService])
], MessagesService);
exports.MessagesService = MessagesService;

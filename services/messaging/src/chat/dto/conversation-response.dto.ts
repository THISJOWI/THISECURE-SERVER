export class ConversationResponse {
  id: string;
  type: 'direct' | 'group';
  name?: string;
  participants: { userId: string; joinedAt: Date; lastReadAt?: Date }[];
  lastMessage?: { text: string; senderId: string; sentAt: Date };
  unreadCount: number;
  createdAt: Date;
  updatedAt: Date;
}

export class MessageResponse {
  id: string;
  conversationId: string;
  senderId: string;
  text: string;
  sentAt: Date;
  editedAt?: Date;
  deletedAt?: Date;
  readBy: { userId: string; readAt: Date }[];
  replyTo?: string;
}

import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { Document, Types } from 'mongoose';

@Schema({ collection: 'messages', timestamps: true })
export class Message extends Document {
  @Prop({ required: true, index: true })
  conversationId: Types.ObjectId;

  @Prop({ required: true })
  senderId: string;

  @Prop({ required: true })
  text: string;

  @Prop({ default: Date.now })
  sentAt: Date;

  @Prop()
  editedAt?: Date;

  @Prop()
  deletedAt?: Date;

  @Prop({ type: [{ userId: String, readAt: Date }], default: [] })
  readBy: { userId: string; readAt: Date }[];

  @Prop()
  replyTo?: Types.ObjectId;

  @Prop()
  ephemeralPublicKey?: string;
}

export const MessageSchema = SchemaFactory.createForClass(Message);

MessageSchema.index({ conversationId: 1, sentAt: -1 });

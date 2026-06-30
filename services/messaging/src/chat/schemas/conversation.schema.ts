import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { Document } from 'mongoose';

@Schema({ collection: 'conversations', timestamps: true })
export class Conversation extends Document {
  @Prop({ required: true, enum: ['direct', 'group'] })
  type: 'direct' | 'group';

  @Prop()
  name?: string;

  @Prop({
    type: [{ userId: String, joinedAt: { type: Date, default: Date.now }, leftAt: Date }],
    default: [],
    validate: [participantsValidator, 'At least 2 participants required'],
  })
  participants: { userId: string; joinedAt: Date; leftAt?: Date }[];

  @Prop({
    type: {
      text: String,
      senderId: String,
      sentAt: Date,
    },
  })
  lastMessage?: { text: string; senderId: string; sentAt: Date };

  @Prop({ default: Date.now })
  createdAt: Date;

  @Prop({ default: Date.now })
  updatedAt: Date;
}

function participantsValidator(val: any[]): boolean {
  return val.length >= 2;
}

export const ConversationSchema = SchemaFactory.createForClass(Conversation);

ConversationSchema.index({ 'participants.userId': 1 });
ConversationSchema.index({ updatedAt: -1 });

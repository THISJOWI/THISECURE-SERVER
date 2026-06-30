import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { Document } from 'mongoose';

@Schema({ collection: 'ldap_users', timestamps: true })
export class LdapUser extends Document {
  @Prop({ required: true, unique: true })
  userId: string;

  @Prop({ required: true })
  email: string;

  @Prop({ required: true })
  fullName: string;

  @Prop({ required: true, index: true })
  domain: string;

  @Prop({ required: true, index: true })
  orgId: string;

  @Prop()
  jpegPhoto?: string;

  @Prop({ default: Date.now })
  lastSyncedAt: Date;
}

export const LdapUserSchema = SchemaFactory.createForClass(LdapUser);

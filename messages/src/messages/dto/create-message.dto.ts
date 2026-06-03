import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export class CreateMessageDto {
  @ApiPropertyOptional({ description: 'ID of the conversation' })
  conversationId?: string;

  @ApiProperty({ description: 'ID of the sender' })
  senderId: string;

  @ApiPropertyOptional({ description: 'ID of the recipient' })
  recipientId?: string;

  @ApiProperty({ description: 'Message content' })
  content: string;

  @ApiPropertyOptional({ description: 'Display name of the sender' })
  sender?: string;

  @ApiPropertyOptional({ description: 'Timestamp of the message' })
  timestamp?: Date;

  @ApiPropertyOptional({ description: 'Whether the message has been read', default: false })
  isRead?: boolean;

  @ApiPropertyOptional({ description: 'Whether the message content is encrypted', default: false })
  isEncrypted?: boolean;

  @ApiPropertyOptional({ description: 'Ephemeral public key for end-to-end encryption' })
  ephemeralPublicKey?: string;
}
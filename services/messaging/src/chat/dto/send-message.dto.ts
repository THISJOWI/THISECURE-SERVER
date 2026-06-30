import { IsString, IsOptional } from 'class-validator';

export class SendMessageDto {
  @IsString()
  conversationId: string;

  @IsString()
  text: string;

  @IsOptional()
  @IsString()
  replyTo?: string;
}

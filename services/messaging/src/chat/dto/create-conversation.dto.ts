import { IsString, IsOptional, IsArray, IsEnum, MinLength } from 'class-validator';

export class CreateConversationDto {
  @IsEnum(['direct', 'group'])
  type: 'direct' | 'group';

  @IsArray()
  @IsString({ each: true })
  participantIds: string[];

  @IsOptional()
  @IsString()
  @MinLength(1)
  name?: string;
}

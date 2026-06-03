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
exports.CreateMessageDto = void 0;
const swagger_1 = require("@nestjs/swagger");
class CreateMessageDto {
}
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'ID of the conversation' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "conversationId", void 0);
__decorate([
    (0, swagger_1.ApiProperty)({ description: 'ID of the sender' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "senderId", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'ID of the recipient' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "recipientId", void 0);
__decorate([
    (0, swagger_1.ApiProperty)({ description: 'Message content' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "content", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'Display name of the sender' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "sender", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'Timestamp of the message' }),
    __metadata("design:type", Date)
], CreateMessageDto.prototype, "timestamp", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'Whether the message has been read', default: false }),
    __metadata("design:type", Boolean)
], CreateMessageDto.prototype, "isRead", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'Whether the message content is encrypted', default: false }),
    __metadata("design:type", Boolean)
], CreateMessageDto.prototype, "isEncrypted", void 0);
__decorate([
    (0, swagger_1.ApiPropertyOptional)({ description: 'Ephemeral public key for end-to-end encryption' }),
    __metadata("design:type", String)
], CreateMessageDto.prototype, "ephemeralPublicKey", void 0);
exports.CreateMessageDto = CreateMessageDto;

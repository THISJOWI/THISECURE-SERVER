import {
  Controller,
  Get,
  Put,
  Delete,
  Param,
  Req,
  Body,
  Res,
  UnauthorizedException,
  NotFoundException,
  BadRequestException,
} from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { Response } from 'express';
import * as jwt from 'jsonwebtoken';
import { LdapService } from '../ldap/ldap.service';

@ApiTags('LDAP Users')
@ApiBearerAuth()
@Controller('ldap-users')
export class LdapUsersController {
  constructor(private readonly ldapService: LdapService) {}

  private extractUserId(req: any): string {
    const auth = req.headers['authorization'] as string;
    if (!auth) throw new UnauthorizedException();
    const token = auth.replace('Bearer ', '');
    try {
      const decoded = jwt.verify(token, process.env.JWT_SECRET) as any;
      return decoded.sub || decoded.userId || decoded.id;
    } catch {
      throw new UnauthorizedException('Invalid or expired token');
    }
  }

  @Get(':domain')
  async getLdapUsers(@Req() req: any, @Param('domain') domain: string) {
    this.extractUserId(req);
    const protocol = req.headers['x-forwarded-proto'] || req.protocol;
    const host = req.headers['x-forwarded-host'] || req.get('host');
    const baseUrl = `${protocol}://${host}`;
    const users = await this.ldapService.getDomainUsers(domain, baseUrl);
    return { success: true, data: users };
  }

  @Get(':domain/:userId/photo')
  async getPhoto(
    @Req() req: any,
    @Param('domain') domain: string,
    @Param('userId') userId: string,
    @Res() res: Response,
  ) {
    this.extractUserId(req);
    const photoBase64 = await this.ldapService.getPhoto(userId, domain);
    if (!photoBase64) {
      throw new NotFoundException('Photo not found');
    }

    const buffer = Buffer.from(photoBase64, 'base64');
    res.setHeader('Content-Type', 'image/jpeg');
    res.setHeader('Content-Length', buffer.length);
    res.setHeader('Cache-Control', 'private, max-age=86400');
    res.send(buffer);
  }

  @Put(':domain/:userId/photo')
  async uploadPhoto(
    @Req() req: any,
    @Param('domain') domain: string,
    @Param('userId') userId: string,
    @Body() body: { photo: string },
  ) {
    const currentUserId = this.extractUserId(req);
    if (currentUserId !== userId) {
      // Allow admins/same user only
      throw new UnauthorizedException('You can only update your own photo');
    }

    if (!body.photo || typeof body.photo !== 'string') {
      throw new BadRequestException('photo field is required (base64 string)');
    }

    // Validate base64
    try {
      Buffer.from(body.photo, 'base64');
    } catch {
      throw new BadRequestException('Invalid base64 encoding');
    }

    await this.ldapService.updatePhoto(userId, domain, body.photo);
    return { success: true, message: 'Photo updated' };
  }

  @Delete(':domain/:userId/photo')
  async deletePhoto(
    @Req() req: any,
    @Param('domain') domain: string,
    @Param('userId') userId: string,
  ) {
    const currentUserId = this.extractUserId(req);
    if (currentUserId !== userId) {
      throw new UnauthorizedException('You can only delete your own photo');
    }

    await this.ldapService.deletePhoto(userId, domain);
    return { success: true, message: 'Photo deleted' };
  }
}

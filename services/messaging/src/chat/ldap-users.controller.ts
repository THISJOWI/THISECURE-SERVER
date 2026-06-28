import { Controller, Get, Param, Req, UnauthorizedException } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
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
    const users = await this.ldapService.getDomainUsers(domain);
    return { success: true, data: users };
  }
}

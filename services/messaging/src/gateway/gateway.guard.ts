import { Injectable, UnauthorizedException } from '@nestjs/common';
import * as jwt from 'jsonwebtoken';

@Injectable()
export class WsGuard {
  validateConnection(auth: { token?: string }): { userId: string; email?: string } {
    const token = auth?.token;
    if (!token) {
      throw new UnauthorizedException('Missing authentication token');
    }

    const secret = process.env.JWT_SECRET;
    if (!secret) {
      throw new UnauthorizedException('JWT_SECRET not configured');
    }

    try {
      const decoded = jwt.verify(token, secret) as any;
      return {
        userId: decoded.sub || decoded.userId || decoded.id,
        email: decoded.email,
      };
    } catch {
      throw new UnauthorizedException('Invalid or expired token');
    }
  }
}

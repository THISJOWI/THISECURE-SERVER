import { Injectable, Logger } from '@nestjs/common';
import { WsGuard } from './gateway.guard';

interface ConnectedClient {
  userId: string;
  email?: string;
  socketId: string;
  connectedAt: Date;
}

@Injectable()
export class GatewayService {
  private readonly logger = new Logger(GatewayService.name);
  private readonly userIdToSockets = new Map<string, Set<string>>();
  private readonly socketIdToUser = new Map<string, ConnectedClient>();

  constructor(private readonly wsGuard: WsGuard) {}

  registerConnection(socketId: string, auth: { token?: string }): ConnectedClient {
    const user = this.wsGuard.validateConnection(auth);

    if (!this.userIdToSockets.has(user.userId)) {
      this.userIdToSockets.set(user.userId, new Set());
    }
    this.userIdToSockets.get(user.userId)!.add(socketId);

    const client: ConnectedClient = {
      userId: user.userId,
      email: user.email,
      socketId,
      connectedAt: new Date(),
    };
    this.socketIdToUser.set(socketId, client);

    return client;
  }

  removeConnection(socketId: string): void {
    const client = this.socketIdToUser.get(socketId);
    if (client) {
      const sockets = this.userIdToSockets.get(client.userId);
      if (sockets) {
        sockets.delete(socketId);
        if (sockets.size === 0) {
          this.userIdToSockets.delete(client.userId);
        }
      }
      this.socketIdToUser.delete(socketId);
    }
  }

  getUserSockets(userId: string): string[] {
    const sockets = this.userIdToSockets.get(userId);
    return sockets ? Array.from(sockets) : [];
  }

  getUserId(socketId: string): string | undefined {
    return this.socketIdToUser.get(socketId)?.userId;
  }

  getConnectionCount(): number {
    return this.socketIdToUser.size;
  }
}

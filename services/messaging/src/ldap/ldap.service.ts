import { Injectable, Logger } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { LdapUser } from './schemas/ldap-user.schema';

export interface LdapUserEvent {
  userId: string;
  email: string;
  fullName: string;
  domain: string;
  orgId: string;
  action: 'LDAP_USER_PROVISIONED' | 'LDAP_USER_DELETED' | 'LDAP_CONFIG_CHANGED';
  timestamp: number;
}

@Injectable()
export class LdapService {
  private readonly logger = new Logger(LdapService.name);

  constructor(
    @InjectModel(LdapUser.name) private readonly ldapUserModel: Model<LdapUser>,
  ) {}

  async upsertUser(event: LdapUserEvent): Promise<void> {
    await this.ldapUserModel.findOneAndUpdate(
      { userId: event.userId },
      {
        $set: {
          email: event.email,
          fullName: event.fullName,
          domain: event.domain,
          orgId: event.orgId,
          lastSyncedAt: new Date(),
        },
      },
      { upsert: true },
    );
    this.logger.log(`Upserted LDAP user ${event.userId} in domain ${event.domain}`);
  }

  async removeUser(userId: string): Promise<void> {
    await this.ldapUserModel.deleteOne({ userId });
    this.logger.log(`Removed LDAP user ${userId}`);
  }

  async getDomainUsers(domain: string) {
    return this.ldapUserModel.find({ domain }).lean();
  }

  async isLdapUser(userId: string): Promise<boolean> {
    const count = await this.ldapUserModel.countDocuments({ userId });
    return count > 0;
  }

  async getAllUsers() {
    return this.ldapUserModel.find().lean();
  }

  async clearDomain(domain: string): Promise<void> {
    await this.ldapUserModel.deleteMany({ domain });
  }
}

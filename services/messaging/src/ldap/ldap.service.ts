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

  async getDomainUsers(domain: string, baseUrl?: string) {
    const users = await this.ldapUserModel.find({ domain }).lean();

    if (baseUrl) {
      const apiPrefix = process.env.API_PREFIX || '';
      return users.map((u: any) => ({
        ...u,
        _id: u._id?.toString(),
        avatarUrl: u.jpegPhoto
          ? `${baseUrl}${apiPrefix}/ldap-users/${domain}/${u.userId}/photo`
          : undefined,
      }));
    }

    return users.map((u: any) => ({
      ...u,
      _id: u._id?.toString(),
    }));
  }

  async updatePhoto(
    userId: string,
    domain: string,
    photoBase64: string,
  ): Promise<void> {
    await this.ldapUserModel.findOneAndUpdate(
      { userId, domain },
      { $set: { jpegPhoto: photoBase64, lastSyncedAt: new Date() } },
    );
  }

  async getPhoto(userId: string, domain: string): Promise<string | null> {
    const user = await this.ldapUserModel.findOne(
      { userId, domain },
      { jpegPhoto: 1 },
    ).lean();
    return user?.jpegPhoto ?? null;
  }

  async deletePhoto(userId: string, domain: string): Promise<void> {
    await this.ldapUserModel.findOneAndUpdate(
      { userId, domain },
      { $set: { jpegPhoto: undefined, lastSyncedAt: new Date() } },
    );
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

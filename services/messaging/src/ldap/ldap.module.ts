import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { LdapUser, LdapUserSchema } from './schemas/ldap-user.schema';
import { LdapConsumerService } from './ldap.consumer';
import { LdapService } from './ldap.service';

@Module({
  imports: [
    MongooseModule.forFeature([{ name: LdapUser.name, schema: LdapUserSchema }]),
  ],
  providers: [LdapService, LdapConsumerService],
  exports: [LdapService],
})
export class LdapModule {}

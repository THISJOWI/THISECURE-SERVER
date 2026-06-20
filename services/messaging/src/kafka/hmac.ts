import * as crypto from 'crypto';

export class HmacService {
  private readonly key: string;

  constructor() {
    const secret = process.env.KAFKA_SIGNING_KEY;
    if (!secret) {
      throw new Error('KAFKA_SIGNING_KEY is not configured');
    }
    this.key = secret;
  }

  sign(message: Buffer | string): string {
    const data = typeof message === 'string' ? Buffer.from(message) : message;
    return crypto.createHmac('sha256', this.key).update(data).digest('base64');
  }

  verify(message: Buffer | string, signature: string): boolean {
    const expected = this.sign(message);
    if (expected.length !== signature.length) return false;
    return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signature));
  }
}

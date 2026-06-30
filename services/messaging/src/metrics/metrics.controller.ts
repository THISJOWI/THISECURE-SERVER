import { Controller, Get, Logger } from '@nestjs/common';
import { Connection } from 'mongoose';
import { InjectConnection } from '@nestjs/mongoose';
import { MetricsService } from './metrics.service';

@Controller()
export class MetricsController {
  private readonly logger = new Logger(MetricsController.name);

  constructor(
    private readonly metricsService: MetricsService,
    @InjectConnection() private readonly mongooseConnection: Connection,
  ) {}

  @Get('/health')
  health() {
    return { status: 'ok', service: 'messaging' };
  }

  @Get('/ready')
  async ready() {
    const dbState = this.mongooseConnection.readyState;
    if (dbState !== 1) {
      return { status: 'not ready', error: 'database unavailable' };
    }
    return { status: 'ready', service: 'messaging' };
  }

  @Get('/metrics')
  async metrics() {
    const body = await this.metricsService.getPrometheusMetrics();
    return body;
  }

  @Get('/metrics/json')
  async metricsJson() {
    const data = await this.metricsService.getJSONMetrics();
    return { success: true, data };
  }
}

import { Injectable, OnModuleInit } from '@nestjs/common';
import * as promClient from 'prom-client';

@Injectable()
export class MetricsService implements OnModuleInit {
  private wsConnectionsActive: promClient.Gauge<string>;

  onModuleInit() {
    promClient.collectDefaultMetrics();

    this.wsConnectionsActive = new promClient.Gauge({
      name: 'ws_connections_active',
      help: 'Number of active WebSocket connections',
    });

    new promClient.Counter({
      name: 'messages_sent_total',
      help: 'Total number of messages sent',
    });

    new promClient.Counter({
      name: 'events_consumed_total',
      help: 'Total number of Kafka events consumed',
    });
  }

  setWsConnections(count: number) {
    if (this.wsConnectionsActive) {
      this.wsConnectionsActive.set(count);
    }
  }

  async getPrometheusMetrics(): Promise<string> {
    return promClient.register.metrics();
  }

  async getJSONMetrics(): Promise<Record<string, any>> {
    return promClient.register.getMetricsAsJSON();
  }
}

/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.metrics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// single source of truth for monitoring.metrics.* - otlp.enabled/prometheus.enabled used to be
// declared as separate @Value literals on both ProbeMetricsRegistryConfig and ProbeMetricsRecorder,
// which could silently drift out of sync
@Component
@ConfigurationProperties(prefix = "monitoring.metrics")
@Data
public class ProbeMetricsProperties {

    private final Otlp otlp = new Otlp();
    private final Prometheus prometheus = new Prometheus();

    @Data
    public static class Otlp {
        private boolean enabled;
        private String endpoint = "http://localhost:4318/v1/metrics";
        private long stepMs = 10000;
        private boolean alertingEnabled;
    }

    @Data
    public static class Prometheus {
        private boolean enabled;
        private int port = 9100;
        private String bindAddress = "0.0.0.0";
    }

}

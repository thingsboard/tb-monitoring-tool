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

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpHttpMetricsSender;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.monitoring.data.MonitoredServiceKey;
import org.thingsboard.monitoring.service.MonitoringReporter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@Slf4j
public class ProbeMetricsRegistryConfig {

    // 4 concurrent scrapers is plenty for a single Prometheus target polling this endpoint - was
    // effectively 1 (setExecutor(null)) before; scrape() is thread-safe so raising it is safe
    private static final int PROMETHEUS_SCRAPE_THREAD_POOL_SIZE = 4;

    private HttpServer prometheusServer;
    private ExecutorService prometheusExecutor;

    @Bean
    public MeterRegistry probeMeterRegistry(ProbeMetricsProperties metricsProperties, MonitoringReporter reporter) throws IOException {
        ProbeMetricsProperties.Otlp otlp = metricsProperties.getOtlp();
        ProbeMetricsProperties.Prometheus prometheus = metricsProperties.getPrometheus();
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        try {
            if (otlp.isEnabled()) {
                composite.add(createOtlpRegistry(otlp.getEndpoint(), otlp.getStepMs(), otlp.isAlertingEnabled(), reporter));
                log.info("Probe metrics: OTLP export enabled, pushing to {}", otlp.getEndpoint());
            }
            if (prometheus.isEnabled()) {
                composite.add(createPrometheusRegistry(prometheus.getPort(), prometheus.getBindAddress()));
                log.info("Probe metrics: Prometheus scrape endpoint enabled on port {}", prometheus.getPort());
            }
        } catch (Exception e) {
            // an already-started OTLP registry's internal publish thread would otherwise leak and
            // block JVM exit, defeating the intended "fail loud" crash on Prometheus bind failure
            composite.close();
            throw e;
        }
        return composite;
    }

    private OtlpMeterRegistry createOtlpRegistry(String endpoint, long stepMs, boolean alertingEnabled, MonitoringReporter reporter) {
        OtlpConfig otlpConfig = new OtlpConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String url() {
                return endpoint;
            }

            @Override
            public Duration step() {
                return Duration.ofMillis(stepMs);
            }

            @Override
            public Map<String, String> resourceAttributes() {
                Map<String, String> attributes = new HashMap<>(OtlpConfig.super.resourceAttributes());
                attributes.putIfAbsent("service.name", "tb-monitoring");
                return attributes;
            }
        };
        OtlpMetricsSender sender = dedupingSender(new OtlpHttpMetricsSender(new HttpUrlConnectionSender()));
        if (alertingEnabled) {
            sender = withAlerting(sender, reporter);
        }
        return OtlpMeterRegistry.builder(otlpConfig).clock(Clock.SYSTEM).metricsSender(sender).build();
    }

    // OtlpMeterRegistry re-exports every gauge on every step_ms tick regardless of change - strips
    // data points unchanged since the last successful send, but still always calls the delegate so
    // withAlerting's OTLP-pipe health check keeps testing connectivity even when nothing changed.
    private static OtlpMetricsSender dedupingSender(OtlpMetricsSender delegate) {
        Map<List<String>, Double> lastSentValues = new ConcurrentHashMap<>();
        return request -> {
            ExportMetricsServiceRequest original = ExportMetricsServiceRequest.parseFrom(request.getMetricsData());
            Map<List<String>, Double> pending = new LinkedHashMap<>();
            ExportMetricsServiceRequest.Builder filtered = ExportMetricsServiceRequest.newBuilder();
            for (ResourceMetrics rm : original.getResourceMetricsList()) {
                ResourceMetrics.Builder rmBuilder = rm.toBuilder().clearScopeMetrics();
                for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                    ScopeMetrics.Builder smBuilder = sm.toBuilder().clearMetrics();
                    for (Metric metric : sm.getMetricsList()) {
                        if (!metric.hasGauge()) {
                            smBuilder.addMetrics(metric); // this app only emits gauges, but don't silently drop other types
                            continue;
                        }
                        List<NumberDataPoint> changed = new ArrayList<>();
                        for (NumberDataPoint point : metric.getGauge().getDataPointsList()) {
                            List<String> key = dataPointKey(metric.getName(), point);
                            double value = point.getAsDouble();
                            if (!Double.valueOf(value).equals(lastSentValues.get(key))) {
                                changed.add(point);
                                pending.put(key, value);
                            }
                        }
                        if (!changed.isEmpty()) {
                            smBuilder.addMetrics(metric.toBuilder().setGauge(Gauge.newBuilder().addAllDataPoints(changed)));
                        }
                    }
                    rmBuilder.addScopeMetrics(smBuilder);
                }
                filtered.addResourceMetrics(rmBuilder);
            }
            delegate.send(OtlpMetricsSender.Request.builder(filtered.build().toByteArray())
                    .address(request.getAddress())
                    .headers(request.getHeaders())
                    .build());
            // only recorded once the send above returned without throwing - a failed push must not be
            // remembered as delivered, or the value would never be retried on a later tick
            lastSentValues.putAll(pending);
        };
    }

    private static List<String> dataPointKey(String metricName, NumberDataPoint point) {
        List<String> key = new ArrayList<>();
        key.add(metricName);
        point.getAttributesList().stream()
                .sorted(Comparator.comparing(KeyValue::getKey))
                .forEach(kv -> key.add(kv.getKey() + "=" + kv.getValue().getStringValue()));
        return key;
    }

    // alerts on real OTLP push failures via Slack/incident, not as a probe metric - a broken
    // metrics pipe can't be expected to report its own breakage
    private static OtlpMetricsSender withAlerting(OtlpMetricsSender delegate, MonitoringReporter reporter) {
        // only calls serviceIsOk() after a preceding failure - otherwise every successful push (every
        // step_ms, forever) would log an "OTLP Export is OK" line and allocate a notification for
        // nothing, on the OTLP publisher thread, when nothing had actually failed
        AtomicBoolean previouslyFailed = new AtomicBoolean(false);
        return request -> {
            try {
                delegate.send(request);
                if (previouslyFailed.compareAndSet(true, false)) {
                    reporter.serviceIsOk(MonitoredServiceKey.OTLP_EXPORT);
                }
            } catch (Exception e) {
                previouslyFailed.set(true);
                reporter.serviceFailure(MonitoredServiceKey.OTLP_EXPORT, e);
                throw e; // preserve OtlpMeterRegistry's own "Failed to publish metrics" warning log
            }
        };
    }

    private PrometheusMeterRegistry createPrometheusRegistry(int port, String bindAddress) throws IOException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheusServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        prometheusServer.createContext("/metrics", exchange -> {
            byte[] response = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        prometheusExecutor = Executors.newFixedThreadPool(PROMETHEUS_SCRAPE_THREAD_POOL_SIZE);
        prometheusServer.setExecutor(prometheusExecutor);
        prometheusServer.start();
        return registry;
    }

    @PreDestroy
    public void shutdown() {
        if (prometheusServer != null) {
            prometheusServer.stop(0);
        }
        if (prometheusExecutor != null) {
            // HttpServer.stop() doesn't shut down a caller-supplied executor - we own its lifecycle
            prometheusExecutor.shutdownNow();
        }
    }

}

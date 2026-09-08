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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.monitoring.config.integration.IntegrationInfo;
import org.thingsboard.monitoring.config.integration.IntegrationType;
import org.thingsboard.monitoring.config.transport.TransportInfo;
import org.thingsboard.monitoring.data.MonitoredServiceKey;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
@Slf4j
public class ProbeMetricsRecorder {

    public static final String PROBE_SUCCESS_METRIC = "probe_success";
    public static final String PROBE_DURATION_METRIC = "probe_duration_ms";
    public static final String MONITORING_HEARTBEAT_METRIC = "tb_monitoring_last_run_timestamp_seconds";
    private static final String KIND_PROBE = "probe";
    private static final String KIND_ACCEPTED = "accepted";

    // shared with BaseHealthChecker/BaseMonitoringService/WsClientFactory to avoid literal drift
    public static final String ACTION_REQUEST = "request";
    public static final String ACTION_WS_UPDATE = "ws_update";
    public static final String ACTION_CONNECT = "connect";
    public static final String ACTION_SUBSCRIBE = "subscribe";

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final String domain;
    private final String label;
    private final String loginEndpoint;
    private final String wsEndpoint;

    private final Map<GaugeKey, RegisteredGauge> gaugeValues = new ConcurrentHashMap<>();
    // Optional-valued so an unresolvable target's negative result is cached too, not re-resolved every
    // call - keyed on type+baseUrl (not the whole TransportInfo/IntegrationInfo, whose equals/hashCode
    // reach mutable state), shared by both transport and integration probes since resolution for
    // either only ever needs the labels resolver + the warned-once set below. Caches the resolved
    // ProbeLabels rather than a final Tags, since resolution is identical for a target's "probe" and
    // "accepted" kind - only the "kind" tag value differs, applied when building Tags from the cache hit.
    private final Map<ProbeKey, Optional<ProbeLabelResolver.ProbeLabels>> labelsCache = new ConcurrentHashMap<>();
    // detects two probes resolving to identical labels, so it's logged instead of silently overwritten
    private final Map<Tags, Object> tagsOwners = new ConcurrentHashMap<>();
    private final Set<Tags> warnedCollisions = ConcurrentHashMap.newKeySet();
    // dedupes the "can't resolve endpoint" warning so a misconfigured target logs it once, not every cycle
    private final Set<ProbeKey> warnedUnresolvable = ConcurrentHashMap.newKeySet();
    // action gauges recorded per probe, so removeProbe can clean them up without knowing them upfront
    private final Map<Tags, Set<String>> actionsByBaseTags = new ConcurrentHashMap<>();
    // tags with fresh data this cycle - protects a colliding sibling from wiping a just-recorded gauge;
    // relies on runChecks() cycles never overlapping (single-threaded executor)
    private final Set<Tags> freshThisCycle = ConcurrentHashMap.newKeySet();

    public ProbeMetricsRecorder(MeterRegistry meterRegistry,
                                 @Value("${monitoring.metrics.otlp.enabled:false}") boolean otlpEnabled,
                                 @Value("${monitoring.metrics.prometheus.enabled:false}") boolean prometheusEnabled,
                                 @Value("${monitoring.domain}") String domain,
                                 @Value("${monitoring.rest.base_url}") String restBaseUrl,
                                 @Value("${monitoring.ws.base_url}") String wsBaseUrl,
                                 @Value("${monitoring.label:}") String label) {
        this.meterRegistry = meterRegistry;
        this.enabled = otlpEnabled || prometheusEnabled;
        this.domain = domain;
        this.label = label;
        this.loginEndpoint = this.enabled ? ProbeLabelResolver.resolveLoginEndpoint(restBaseUrl) : null;
        this.wsEndpoint = this.enabled ? ProbeLabelResolver.resolveWsEndpoint(wsBaseUrl) : null;
    }

    // resets which Tags have fresh data this cycle - called once at the start of each runChecks()
    public void startCycle() {
        freshThisCycle.clear();
    }

    public void recordProbe(Object serviceKey, boolean success) {
        withTags(serviceKey, "record", tags -> {
            warnIfLabelCollision(serviceKey, tags);
            setGauge(PROBE_SUCCESS_METRIC, tags, success ? 1d : 0d);
            freshThisCycle.add(tags);
        });
    }

    // takes nanos (like reportLatency) rather than ms, so callers measuring with TbStopWatch don't
    // each have to repeat their own "/ 1_000_000" - this method is the one place that knows the
    // exported gauge's unit is milliseconds
    public void recordActionDuration(Object serviceKey, String action, long durationNanos) {
        withTags(serviceKey, "record action duration", tags -> {
            actionsByBaseTags.computeIfAbsent(tags, k -> ConcurrentHashMap.newKeySet()).add(action);
            setGauge(PROBE_DURATION_METRIC, tags.and("action", action), durationNanos / 1_000_000d);
        });
    }

    // removes just this action's duration gauge; skips if a colliding sibling already has fresh data
    public void removeActionDuration(Object serviceKey, String action) {
        withTags(serviceKey, "remove action duration", tags -> {
            if (freshThisCycle.contains(tags)) {
                log.debug("Skipping removal of action duration for [{}] action {} - tags {} already have fresh data this cycle from a colliding target", serviceKey, action, tags);
                return;
            }
            removeGauge(PROBE_DURATION_METRIC, tags.and("action", action));
            Set<String> actions = actionsByBaseTags.get(tags);
            if (actions != null) {
                actions.remove(action);
            }
        });
    }

    // true once the transport/integration itself acknowledges a message, independent of the login/WS session
    public void recordAcceptedProbe(Object serviceKey, boolean success) {
        if (!enabled) {
            return;
        }
        try {
            Tags tags = acceptedTagsFor(serviceKey);
            if (tags == null) {
                return;
            }
            warnIfLabelCollision(serviceKey, tags);
            setGauge(PROBE_SUCCESS_METRIC, tags, success ? 1d : 0d);
            freshThisCycle.add(tags);
        } catch (Exception e) {
            log.warn("Failed to record accepted probe metric for [{}]", serviceKey, e);
        }
    }

    private Tags acceptedTagsFor(Object serviceKey) {
        if (serviceKey instanceof TransportInfo transportInfo) {
            return tagsFor(ProbeKey.of(transportInfo),
                    () -> ProbeLabelResolver.resolveTransportLabels(transportInfo.getType(), transportInfo.getTarget().getBaseUrl()), KIND_ACCEPTED);
        } else if (serviceKey instanceof IntegrationInfo integrationInfo) {
            return tagsFor(ProbeKey.of(integrationInfo),
                    () -> ProbeLabelResolver.resolveIntegrationLabels(integrationInfo.getType(), integrationInfo.getBaseUrl()), KIND_ACCEPTED);
        }
        return null;
    }

    // PERMANENT always removes (decommissioning); STALE_THIS_CYCLE skips if a colliding sibling is fresh
    public enum Removal {
        PERMANENT, STALE_THIS_CYCLE
    }

    public void removeProbe(Object serviceKey, Removal removal) {
        withTags(serviceKey, "remove", tags -> {
            if (removal == Removal.STALE_THIS_CYCLE && freshThisCycle.contains(tags)) {
                log.debug("Skipping removal of probe metric for [{}] - tags {} already have fresh data this cycle from a colliding target", serviceKey, tags);
                return;
            }
            removeGauge(PROBE_SUCCESS_METRIC, tags);
            Set<String> actions = actionsByBaseTags.remove(tags);
            if (actions != null) {
                actions.forEach(action -> removeGauge(PROBE_DURATION_METRIC, tags.and("action", action)));
            }
            if (removal == Removal.PERMANENT) {
                clearCollisionState(tags);
            }
            freshThisCycle.remove(tags); // a permanent removal must not leave a stale fresh-flag behind
        });
        // only on permanent removal - a stale removal runs every unhealthy cycle and would defeat the cache
        if (removal == Removal.PERMANENT) {
            evictResolutionCache(serviceKey);
        }
    }

    // see removeProbe(Object, Removal) for what PERMANENT vs STALE_THIS_CYCLE mean here
    public void removeAcceptedProbe(Object serviceKey, Removal removal) {
        if (!enabled) {
            return;
        }
        try {
            Tags tags = acceptedTagsFor(serviceKey);
            if (tags != null) {
                if (removal == Removal.STALE_THIS_CYCLE && freshThisCycle.contains(tags)) {
                    log.debug("Skipping removal of accepted probe metric for [{}] - tags {} already have fresh data this cycle from a colliding target", serviceKey, tags);
                } else {
                    removeGauge(PROBE_SUCCESS_METRIC, tags);
                    freshThisCycle.remove(tags);
                    if (removal == Removal.PERMANENT) {
                        clearCollisionState(tags);
                    }
                }
            }
            // only on permanent removal - a stale removal runs every healthy cycle and would defeat
            // the cache. Evicted unconditionally, even when tags resolved to null above, otherwise a
            // decommissioned unresolvable target leaks its Optional.empty() entry here forever.
            if (removal == Removal.PERMANENT) {
                evictResolutionCache(serviceKey);
            }
        } catch (Exception e) {
            log.warn("Failed to remove accepted probe metric for [{}]", serviceKey, e);
        }
    }

    // shared by removeProbe/removeAcceptedProbe's PERMANENT branch - the resolution cache and its
    // warned-once dedup are keyed on type+baseUrl only, independent of "probe" vs "accepted" kind
    private void evictResolutionCache(Object serviceKey) {
        ProbeKey key = keyFor(serviceKey);
        if (key != null) {
            labelsCache.remove(key);
            warnedUnresolvable.remove(key);
        }
    }

    private static ProbeKey keyFor(Object serviceKey) {
        if (serviceKey instanceof TransportInfo transportInfo) {
            return ProbeKey.of(transportInfo);
        } else if (serviceKey instanceof IntegrationInfo integrationInfo) {
            return ProbeKey.of(integrationInfo);
        }
        return null;
    }

    // distinguishes "the prober is dead" (this goes stale) from "a target is down" (probe_success does)
    public void recordHeartbeat() {
        if (!enabled) {
            return;
        }
        try {
            setGauge(MONITORING_HEARTBEAT_METRIC, Tags.of("domain", domain, "label", label), (double) (System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            log.warn("Failed to record monitoring heartbeat metric", e);
        }
    }

    private void clearCollisionState(Tags tags) {
        tagsOwners.remove(tags);
        warnedCollisions.remove(tags);
    }

    private void warnIfLabelCollision(Object serviceKey, Tags tags) {
        Object previousOwner = tagsOwners.put(tags, serviceKey);
        if (previousOwner != null && !previousOwner.equals(serviceKey) && warnedCollisions.add(tags)) {
            log.warn("Probe metrics collision: [{}] and [{}] both resolve to labels {} - " +
                    "one will silently overwrite the other's gauge every cycle", previousOwner, serviceKey, tags);
        }
    }

    private void withTags(Object serviceKey, String verb, Consumer<Tags> body) {
        if (!enabled) {
            return;
        }
        try {
            Tags tags = resolveTags(serviceKey);
            if (tags == null) {
                return; // GENERAL, EDQS, an unresolvable transport endpoint, or anything outside the documented label taxonomy
            }
            body.accept(tags);
        } catch (Exception e) {
            log.warn("Failed to {} probe metric for [{}]", verb, serviceKey, e);
        }
    }

    private Tags resolveTags(Object serviceKey) {
        if (serviceKey instanceof TransportInfo transportInfo) {
            return tagsFor(ProbeKey.of(transportInfo),
                    () -> ProbeLabelResolver.resolveTransportLabels(transportInfo.getType(), transportInfo.getTarget().getBaseUrl()), KIND_PROBE);
        } else if (serviceKey instanceof IntegrationInfo integrationInfo) {
            return tagsFor(ProbeKey.of(integrationInfo),
                    () -> ProbeLabelResolver.resolveIntegrationLabels(integrationInfo.getType(), integrationInfo.getBaseUrl()), KIND_PROBE);
        } else if (loginEndpoint != null && MonitoredServiceKey.LOGIN.equals(serviceKey)) {
            return baseTags("login", loginEndpoint, KIND_PROBE);
        } else if (wsEndpoint != null && MonitoredServiceKey.WS.equals(serviceKey)) {
            return baseTags("ws", wsEndpoint, KIND_PROBE);
        }
        return null;
    }

    // shared by transport and integration probes - resolution (and its negative-cache/warn-once
    // dedup) only depends on type+baseUrl; "kind" only affects which Tags come back from a cache hit
    private Tags tagsFor(ProbeKey key, Supplier<ProbeLabelResolver.ProbeLabels> resolver, String kind) {
        Optional<ProbeLabelResolver.ProbeLabels> labels = labelsCache.computeIfAbsent(key, k -> {
            ProbeLabelResolver.ProbeLabels resolved = resolver.get();
            if (resolved == null && warnedUnresolvable.add(k)) {
                log.warn("Failed to resolve host:port from {} base URL \"{}\" (missing scheme?) - its probe metrics will not be recorded",
                        k.type() instanceof IntegrationType ? "integration" : "transport", k.baseUrl());
            }
            return Optional.ofNullable(resolved);
        });
        return labels.map(l -> baseTags(l.check(), l.endpoint(), kind)).orElse(null);
    }

    private Tags baseTags(String check, String endpoint, String kind) {
        return Tags.of("domain", domain, "check", check, "endpoint", endpoint, "kind", kind, "label", label);
    }

    private void setGauge(String metricName, Tags tags, double value) {
        gaugeValues.computeIfAbsent(new GaugeKey(metricName, tags), k -> {
            AtomicReference<Double> ref = new AtomicReference<>(value);
            Gauge gauge = Gauge.builder(metricName, ref, r -> r.get())
                    .tags(tags)
                    .register(meterRegistry);
            return new RegisteredGauge(gauge, ref);
        }).value().set(value);
    }

    private void removeGauge(String metricName, Tags tags) {
        // direct removal by the Gauge reference captured at registration time - no full-registry scan
        RegisteredGauge registered = gaugeValues.remove(new GaugeKey(metricName, tags));
        if (registered != null) {
            meterRegistry.remove(registered.gauge());
        }
    }

    private record GaugeKey(String metricName, Tags tags) {
    }

    private record RegisteredGauge(Gauge gauge, AtomicReference<Double> value) {
    }

    private record ProbeKey(Enum<?> type, String baseUrl) {
        static ProbeKey of(TransportInfo info) {
            return new ProbeKey(info.getType(), info.getTarget().getBaseUrl());
        }

        static ProbeKey of(IntegrationInfo info) {
            return new ProbeKey(info.getType(), info.getBaseUrl());
        }
    }

}

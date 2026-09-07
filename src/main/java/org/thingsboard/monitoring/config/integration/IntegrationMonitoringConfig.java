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
package org.thingsboard.monitoring.config.integration;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.monitoring.config.BaseMonitoringConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public abstract class IntegrationMonitoringConfig extends BaseMonitoringConfig<IntegrationMonitoringTarget> {

    private IntegrationMonitoringTarget target;

    // Only one target is supported per integration type (unlike transports); a missing `target:`
    // block just means no checks run for it, rather than an NPE from List.of(null).
    @Override
    public List<IntegrationMonitoringTarget> getTargets() {
        return target != null ? List.of(target) : Collections.emptyList();
    }

    public abstract IntegrationType getIntegrationType();

    // The ${MONITORING:KEY} params the integration/*/integration.json template for this type needs
    // to render into a real Integration - every type must decide explicitly, since MQTT's params
    // are a disjoint set (host/port/client id/username parsed out of the URL) rather than an
    // extension of the base-URL-only case HTTP/CoAP need.
    public abstract Map<String, String> buildTemplateParams(IntegrationMonitoringTarget target, String routingKey);

    protected static Map<String, String> baseUrlTemplateParams(IntegrationMonitoringTarget target, String routingKey) {
        return Map.of(
                "BASE_URL", target.getBaseUrl(),
                "ROUTING_KEY", routingKey
        );
    }

}

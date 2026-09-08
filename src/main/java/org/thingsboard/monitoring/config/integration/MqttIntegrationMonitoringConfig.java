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
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "monitoring.integrations.mqtt.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "monitoring.integrations.mqtt")
@Data
@EqualsAndHashCode(callSuper = true)
public class MqttIntegrationMonitoringConfig extends IntegrationMonitoringConfig {

    // Credentials for the *integration's own outbound* connection to the broker at target.baseUrl -
    // separate from monitoring.rest credentials, which only authenticate against the ThingsBoard REST API.
    private String username;

    @Override
    public IntegrationType getIntegrationType() {
        return IntegrationType.MQTT;
    }

    @Override
    public Map<String, String> buildTemplateParams(IntegrationMonitoringTarget target, String routingKey) {
        URI uri = URI.create(target.getBaseUrl());
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid MQTT base_url '" + target.getBaseUrl() +
                    "' - expected a scheme, e.g. tcp://" + target.getBaseUrl());
        }
        boolean ssl = "ssl".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort();
        if (port == -1) {
            port = ssl ? 8883 : 1883;
        }
        return Map.of(
                "HOST", uri.getHost(),
                "PORT", String.valueOf(port),
                // MqttUtils.connect() (this tool's own probe client) already switches to TLS for an
                // ssl:// base_url via Paho's own scheme handling - the *provisioned* Integration needs
                // telling separately, or it ends up trying plaintext against a TLS-only port.
                "SSL", String.valueOf(ssl),
                "ROUTING_KEY", routingKey,
                "CLIENT_ID_SUFFIX", RandomStringUtils.secure().nextNumeric(6),
                "USERNAME", Objects.requireNonNullElse(username, "")
        );
    }

}

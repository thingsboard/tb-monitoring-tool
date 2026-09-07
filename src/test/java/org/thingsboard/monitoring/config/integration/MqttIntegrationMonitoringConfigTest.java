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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttIntegrationMonitoringConfigTest {

    @Test
    void rejectsBaseUrlMissingScheme() {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("broker.example.com:1883");

        assertThatThrownBy(() -> new MqttIntegrationMonitoringConfig().buildTemplateParams(target, "routing-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broker.example.com:1883");
    }

    @Test
    void usesPortFromUrlWhenPresent() {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com:1884");

        Map<String, String> params = new MqttIntegrationMonitoringConfig().buildTemplateParams(target, "routing-key");

        assertThat(params.get("HOST")).isEqualTo("broker.example.com");
        assertThat(params.get("PORT")).isEqualTo("1884");
    }

    @Test
    void defaultsToPort1883WhenUrlOmitsItAndSchemeIsNotSsl() {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com");

        Map<String, String> params = new MqttIntegrationMonitoringConfig().buildTemplateParams(target, "routing-key");

        assertThat(params.get("PORT")).isEqualTo("1883");
    }

    @Test
    void defaultsToPort8883WhenUrlOmitsItAndSchemeIsSsl() {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("ssl://broker.example.com");

        Map<String, String> params = new MqttIntegrationMonitoringConfig().buildTemplateParams(target, "routing-key");

        assertThat(params.get("PORT")).isEqualTo("8883");
    }

    @Test
    void substitutesUsernameAndRoutingKeyAndGeneratesClientIdSuffix() {
        MqttIntegrationMonitoringConfig config = new MqttIntegrationMonitoringConfig();
        config.setUsername("monitor");
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com");

        Map<String, String> params = config.buildTemplateParams(target, "routing-key");

        assertThat(params.get("USERNAME")).isEqualTo("monitor");
        assertThat(params.get("ROUTING_KEY")).isEqualTo("routing-key");
        assertThat(params.get("CLIENT_ID_SUFFIX")).matches("\\d{6}");
    }

    @Test
    void defaultsUsernameToEmptyStringWhenNotSet() {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com");

        Map<String, String> params = new MqttIntegrationMonitoringConfig().buildTemplateParams(target, "routing-key");

        assertThat(params.get("USERNAME")).isEmpty();
    }

}

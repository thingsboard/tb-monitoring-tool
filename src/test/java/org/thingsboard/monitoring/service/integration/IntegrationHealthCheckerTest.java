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
package org.thingsboard.monitoring.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.config.DeviceConfig;
import org.thingsboard.monitoring.config.integration.CoapIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.HttpIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationInfo;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.MqttIntegrationMonitoringConfig;
import org.thingsboard.monitoring.service.BaseHealthChecker;
import org.thingsboard.monitoring.service.integration.impl.CoapIntegrationHealthChecker;
import org.thingsboard.monitoring.service.integration.impl.HttpIntegrationHealthChecker;
import org.thingsboard.monitoring.service.integration.impl.MqttIntegrationHealthChecker;

import static org.assertj.core.api.Assertions.assertThat;

// Pins down the two implicit contracts every IntegrationHealthChecker relies on: the test payload
// shape the TBEL decoder in converter.json expects, and the getKey() the dashboard series names
// (ihttpRequestLatency, etc.) are built from.
class IntegrationHealthCheckerTest {

    private static IntegrationMonitoringTarget targetFor(String deviceName) {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        DeviceConfig device = new DeviceConfig();
        device.setName(deviceName);
        target.setDevice(device);
        target.setBaseUrl("http://example.com");
        return target;
    }

    @Test
    void createTestPayloadMatchesConverterJsonContract() {
        HttpIntegrationHealthChecker checker = new HttpIntegrationHealthChecker(
                new HttpIntegrationMonitoringConfig(), targetFor("My device"));

        JsonNode payload = JacksonUtil.toJsonNode(checker.createTestPayload("abc-123", BaseHealthChecker.TEST_TELEMETRY_KEY));

        assertThat(payload.get("device").asText()).isEqualTo("My device");
        assertThat(payload.get("telemetry").get("testData").asText()).isEqualTo("abc-123");
    }

    @Test
    void getKeyIsDistinctPerIntegrationType() {
        // NOT getCheckKey()'s "ihttp"/"icoap"/"imqtt" - this key builds the ThingsBoard telemetry
        // latency key, which keeps its older, separate naming so an existing deployment's dashboard
        // history doesn't get orphaned (see IntegrationHealthChecker.getKey())
        HttpIntegrationHealthChecker http = new HttpIntegrationHealthChecker(new HttpIntegrationMonitoringConfig(), targetFor("d"));
        CoapIntegrationHealthChecker coap = new CoapIntegrationHealthChecker(new CoapIntegrationMonitoringConfig(), targetFor("d"));
        MqttIntegrationHealthChecker mqtt = new MqttIntegrationHealthChecker(new MqttIntegrationMonitoringConfig(), targetFor("d"));

        assertThat(http.getKey()).isEqualTo("httpIntegration");
        assertThat(coap.getKey()).isEqualTo("coapIntegration");
        assertThat(mqtt.getKey()).isEqualTo("mqttIntegration");
    }

    @Test
    void getInfoReturnsIntegrationSpecificShortName() {
        HttpIntegrationHealthChecker checker = new HttpIntegrationHealthChecker(
                new HttpIntegrationMonitoringConfig(), targetFor("d"));

        Object info = checker.getInfo();

        assertThat(info).isInstanceOf(IntegrationInfo.class);
        assertThat(((IntegrationInfo) info).getShortName()).isEqualTo("HTTP integration");
    }

}

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
package org.thingsboard.monitoring.service.integration.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.IntegrationType;
import org.thingsboard.monitoring.config.integration.MqttIntegrationMonitoringConfig;
import org.thingsboard.monitoring.service.integration.IntegrationHealthChecker;
import org.thingsboard.monitoring.util.MqttUtils;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class MqttIntegrationHealthChecker extends IntegrationHealthChecker<MqttIntegrationMonitoringConfig> {

    private MqttClient mqttClient;
    private String topic;
    private int qos;

    public MqttIntegrationHealthChecker(MqttIntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        super(config, target);
    }

    @Override
    protected void initClient() throws Exception {
        if (mqttClient == null || !mqttClient.isConnected()) {
            String userName = target.getIntegration().getConfiguration().get("clientConfiguration").get("credentials").get("username").asText();

            // Read from the saved Integration rather than rebuilding "monitoring/<routingKey>" here,
            // so this can't drift from the topicFilters entry in integration/mqtt/integration.json.
            JsonNode topicFilter = target.getIntegration().getConfiguration().get("topicFilters").get(0);
            topic = topicFilter.get("filter").asText();
            qos = topicFilter.get("qos").asInt();

            mqttClient = MqttUtils.connect(target.getBaseUrl(), userName, config.getRequestTimeoutMs());
            log.debug("Initialized MQTT client for URI {}", mqttClient.getServerURI());
        }
    }

    @Override
    protected void sendTestPayload(String payload) throws Exception {
        MqttMessage message = new MqttMessage();
        message.setPayload(payload.getBytes());
        message.setQos(qos);
        mqttClient.publish(topic, message);
    }

    @Override
    protected void destroyClient() throws Exception {
        if (mqttClient != null) {
            mqttClient.disconnect();
            mqttClient = null;
            log.info("Disconnected MQTT client");
        }
    }

    @Override
    protected IntegrationType getIntegrationType() {
        return IntegrationType.MQTT;
    }

}

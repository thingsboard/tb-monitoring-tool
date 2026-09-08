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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.MqttIntegrationMonitoringConfig;
import org.thingsboard.monitoring.util.MqttUtils;
import org.thingsboard.server.common.data.integration.Integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

// Pins down the part of MqttIntegrationHealthChecker that's specific to it (parsing topic/qos out
// of the saved Integration's topicFilters) without making a real network connection - MqttUtils.connect
// is stubbed statically to hand back a mock client instead.
class MqttIntegrationHealthCheckerTest {

    @Test
    void parsesTopicAndQosFromSavedIntegrationAndPublishesWithThem() throws Exception {
        MqttIntegrationMonitoringConfig config = new MqttIntegrationMonitoringConfig();
        config.setRequestTimeoutMs(4000);
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com:1883");
        target.setIntegration(integrationWithTopicFilter("monitoring/abc-123", 1, "monitor"));

        MqttIntegrationHealthChecker checker = new MqttIntegrationHealthChecker(config, target);
        MqttClient mockClient = mock(MqttClient.class);

        try (MockedStatic<MqttUtils> mqttUtils = mockStatic(MqttUtils.class)) {
            mqttUtils.when(() -> MqttUtils.connect(anyString(), anyString(), anyInt())).thenReturn(mockClient);
            checker.initClient();
        }
        checker.sendTestPayload("test-payload");

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("monitoring/abc-123"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getQos()).isEqualTo(1);
        assertThat(new String(messageCaptor.getValue().getPayload())).isEqualTo("test-payload");
    }

    @Test
    void sendAcceptedTestPayload_forcesQos1EvenWhenSavedTopicFilterIsQos0() throws Exception {
        // regression test: MqttTransportHealthChecker forces QoS 1 for the accepted fallback because
        // QoS 0's publish() never confirms broker receipt, defeating the fallback's whole purpose.
        // This checker reads qos from the saved Integration's topic filter for the normal path, so a
        // QoS-0 filter (editable in the TB UI) must not carry over to the accepted fallback too.
        MqttIntegrationMonitoringConfig config = new MqttIntegrationMonitoringConfig();
        config.setRequestTimeoutMs(4000);
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("tcp://broker.example.com:1883");
        target.setIntegration(integrationWithTopicFilter("monitoring/abc-123", 0, "monitor"));

        MqttIntegrationHealthChecker checker = new MqttIntegrationHealthChecker(config, target);
        MqttClient mockClient = mock(MqttClient.class);

        try (MockedStatic<MqttUtils> mqttUtils = mockStatic(MqttUtils.class)) {
            mqttUtils.when(() -> MqttUtils.connect(anyString(), anyString(), anyInt())).thenReturn(mockClient);
            checker.initClient();
        }
        checker.sendAcceptedTestPayload("accepted-payload");

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("monitoring/abc-123"), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getQos()).isEqualTo(1);
    }

    private static Integration integrationWithTopicFilter(String filter, int qos, String username) {
        ObjectNode credentials = JacksonUtil.newObjectNode().put("username", username);
        ObjectNode clientConfiguration = JacksonUtil.newObjectNode();
        clientConfiguration.set("credentials", credentials);

        ObjectNode configuration = JacksonUtil.newObjectNode();
        configuration.set("clientConfiguration", clientConfiguration);
        ArrayNode topicFilters = configuration.putArray("topicFilters");
        topicFilters.add(JacksonUtil.newObjectNode().put("filter", filter).put("qos", qos));

        Integration integration = new Integration();
        integration.setConfiguration(configuration);
        return integration;
    }

}

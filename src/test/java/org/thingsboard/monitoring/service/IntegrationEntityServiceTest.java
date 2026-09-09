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
package org.thingsboard.monitoring.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.config.integration.CoapIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.HttpIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.MqttIntegrationMonitoringConfig;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.converter.Converter;
import org.thingsboard.server.common.data.id.ConverterId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.IntegrationId;
import org.thingsboard.server.common.data.integration.Integration;
import org.thingsboard.server.common.data.page.PageData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationEntityServiceTest {

    @Mock
    private TbClient tbClient;

    private IntegrationEntityService entityService;

    @BeforeEach
    void setUp() {
        entityService = new IntegrationEntityService(tbClient);
    }

    // lenient(): whichever entity a given test makes "already exist" skips its save* stub,
    // which strict stubbing would otherwise flag as unused.
    private void stubNothingExistsYet() {
        lenient().when(tbClient.getTenantDevice(any())).thenReturn(Optional.empty());
        lenient().when(tbClient.saveDevice(any())).thenAnswer(inv -> {
            Device device = inv.getArgument(0);
            device.setId(new DeviceId(UUID.randomUUID()));
            return device;
        });
        lenient().when(tbClient.getConverters(any())).thenReturn(PageData.emptyPageData());
        lenient().when(tbClient.saveConverter(any())).thenAnswer(inv -> {
            Converter converter = inv.getArgument(0);
            converter.setId(new ConverterId(UUID.randomUUID()));
            return converter;
        });
        lenient().when(tbClient.getIntegrations(any())).thenReturn(PageData.emptyPageData());
        lenient().when(tbClient.saveIntegration(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // Pins down the integration/*/integration.json template rendering: the substitution is plain regex
    // replace on raw text before JSON parsing, so a mis-ordered/misspelled placeholder or an unquoted
    // vs. quoted numeric field would otherwise only surface against a real PE server.
    @Nested
    class TemplateRendering {

        @BeforeEach
        void setUp() {
            stubNothingExistsYet();
        }

        @Test
        void rendersHttpIntegrationTemplate() {
            IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
            target.setBaseUrl("http://example.com");

            entityService.checkEntities(new HttpIntegrationMonitoringConfig(), target);

            Integration integration = target.getIntegration();
            JsonNode configuration = integration.getConfiguration();
            assertThat(configuration.get("baseUrl").asText()).isEqualTo("http://example.com");
            assertThat(configuration.get("httpEndpoint").asText())
                    .isEqualTo("http://example.com/api/v1/integrations/http/" + integration.getRoutingKey());
        }

        @Test
        void rendersCoapIntegrationTemplate() {
            IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
            target.setBaseUrl("coap://example.com");

            entityService.checkEntities(new CoapIntegrationMonitoringConfig(), target);

            Integration integration = target.getIntegration();
            JsonNode clientConfiguration = integration.getConfiguration().get("clientConfiguration");
            assertThat(clientConfiguration.get("baseUrl").asText()).isEqualTo("coap://example.com");
            assertThat(clientConfiguration.get("coapEndpoint").asText())
                    .isEqualTo("coap://example.com/i/" + integration.getRoutingKey());
        }

        @Test
        void rendersMqttIntegrationTemplateWithNumericPortFromUrl() {
            MqttIntegrationMonitoringConfig config = new MqttIntegrationMonitoringConfig();
            config.setUsername("monitor");
            IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
            target.setBaseUrl("tcp://broker.example.com:1884");

            entityService.checkEntities(config, target);

            Integration integration = target.getIntegration();
            JsonNode clientConfiguration = integration.getConfiguration().get("clientConfiguration");
            assertThat(clientConfiguration.get("host").asText()).isEqualTo("broker.example.com");
            assertThat(clientConfiguration.get("port").isInt()).isTrue();
            assertThat(clientConfiguration.get("port").asInt()).isEqualTo(1884);
            assertThat(clientConfiguration.get("credentials").get("username").asText()).isEqualTo("monitor");
            assertThat(integration.getConfiguration().get("topicFilters").get(0).get("filter").asText())
                    .isEqualTo("monitoring/" + integration.getRoutingKey());
        }

        @Test
        void rendersMqttIntegrationTemplateWithDefaultPortWhenUrlOmitsIt() {
            IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
            target.setBaseUrl("tcp://broker.example.com");

            entityService.checkEntities(new MqttIntegrationMonitoringConfig(), target);

            Integration integration = target.getIntegration();
            assertThat(integration.getConfiguration().get("clientConfiguration").get("port").asInt()).isEqualTo(1883);
            assertThat(integration.getConfiguration().get("clientConfiguration").get("ssl").asBoolean()).isFalse();
        }

        @Test
        void rendersMqttIntegrationTemplateWithDefaultSslPortWhenUrlOmitsIt() {
            // regression test: the provisioned Integration used to be hardcoded to ssl=false
            // regardless of scheme, so an ssl:// target's Integration would try plaintext against
            // what is usually a TLS-only port and never connect - even though this tool's own probe
            // client (MqttUtils.connect) already switches to TLS correctly for the same URL
            IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
            target.setBaseUrl("ssl://broker.example.com");

            entityService.checkEntities(new MqttIntegrationMonitoringConfig(), target);

            Integration integration = target.getIntegration();
            assertThat(integration.getConfiguration().get("clientConfiguration").get("port").asInt()).isEqualTo(8883);
            assertThat(integration.getConfiguration().get("clientConfiguration").get("ssl").asBoolean()).isTrue();
        }
    }

    // Covers the "already exists, reuse it" branches of getOrCreateIntegrationDevice/
    // getOrCreateMonitoringConverter/getOrCreateIntegration, including the exact-name filter
    // applied after the name-search - previously only the "doesn't exist yet" branch was tested.
    @Nested
    class ReuseExisting {

        private IntegrationMonitoringTarget target;

        @BeforeEach
        void setUp() {
            target = new IntegrationMonitoringTarget();
            target.setBaseUrl("http://example.com");

            // Defaults: nothing exists yet - each test overrides only the lookup(s) it cares about.
            stubNothingExistsYet();
        }

        @Test
        void reusesExistingDeviceAndSkipsCreation() {
            Device existingDevice = new Device(new DeviceId(UUID.randomUUID()));
            existingDevice.setName("HTTP integration - http://example.com");
            when(tbClient.getTenantDevice(any())).thenReturn(Optional.of(existingDevice));

            entityService.checkEntities(new HttpIntegrationMonitoringConfig(), target);

            assertThat(target.getDevice().getId()).isEqualTo(existingDevice.getId().getId());
            verify(tbClient, never()).saveDevice(any());
        }

        @Test
        void reusesExistingConverterWhenNameMatchesExactly() {
            Converter existingConverter = new Converter();
            existingConverter.setId(new ConverterId(UUID.randomUUID()));
            existingConverter.setName("[Monitoring] Default converter");
            when(tbClient.getConverters(any())).thenReturn(new PageData<>(List.of(existingConverter), 1, 1, false));

            entityService.checkEntities(new HttpIntegrationMonitoringConfig(), target);

            assertThat(target.getIntegration().getDefaultConverterId()).isEqualTo(existingConverter.getId());
            verify(tbClient, never()).saveConverter(any());
        }

        @Test
        void ignoresPartialNameMatchAndCreatesNewConverter() {
            Converter unrelatedConverter = new Converter();
            unrelatedConverter.setId(new ConverterId(UUID.randomUUID()));
            unrelatedConverter.setName("[Monitoring] Default converter (old)");
            when(tbClient.getConverters(any())).thenReturn(new PageData<>(List.of(unrelatedConverter), 1, 1, false));

            entityService.checkEntities(new HttpIntegrationMonitoringConfig(), target);

            assertThat(target.getIntegration().getDefaultConverterId()).isNotEqualTo(unrelatedConverter.getId());
            verify(tbClient).saveConverter(any());
        }

        @Test
        void reusesExistingIntegrationWhenNameMatchesExactly() {
            Integration existingIntegration = new Integration();
            existingIntegration.setId(new IntegrationId(UUID.randomUUID()));
            existingIntegration.setName("HTTP integration - http://example.com");
            when(tbClient.getIntegrations(any())).thenReturn(new PageData<>(List.of(existingIntegration), 1, 1, false));

            entityService.checkEntities(new HttpIntegrationMonitoringConfig(), target);

            assertThat(target.getIntegration().getId()).isEqualTo(existingIntegration.getId());
            verify(tbClient, never()).saveIntegration(any());
        }
    }

}

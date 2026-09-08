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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.config.DeviceConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.util.ResourceUtils;
import org.thingsboard.monitoring.util.SearchUtils;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.converter.Converter;
import org.thingsboard.server.common.data.id.ConverterId;
import org.thingsboard.server.common.data.integration.Integration;

import java.util.Map;
import java.util.UUID;

// Provisions the ThingsBoard entities (device, converter, integration) an Integrations Framework
// health check needs - the integration counterpart to how MonitoringEntityService provisions
// transport devices. Kept separate for the same reason PublicSharingService is: a distinct,
// self-contained concern that would otherwise bloat MonitoringEntityService further.
@Service
@Slf4j
@RequiredArgsConstructor
public class IntegrationEntityService {

    private final TbClient tbClient;

    // Integrations Framework is PE-only; this codepath is only ever exercised when an
    // integration check is enabled in config, which only makes sense against a PE target.
    public void checkEntities(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        Device device = getOrCreateIntegrationDevice(config, target);
        DeviceConfig deviceConfig = new DeviceConfig();
        deviceConfig.setId(device.getId().toString());
        deviceConfig.setName(device.getName());
        target.setDevice(deviceConfig);

        Converter converter = getOrCreateMonitoringConverter();
        Integration integration = getOrCreateIntegration(config, target, converter.getId());
        target.setIntegration(integration);
    }

    private String integrationEntityName(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        return String.format("%s %s integration - %s", target.getNamePrefix(), config.getIntegrationType().getName(), target.getBaseUrl()).trim();
    }

    private Device getOrCreateIntegrationDevice(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        String deviceName = integrationEntityName(config, target);
        return tbClient.getTenantDevice(deviceName)
                .orElseGet(() -> {
                    Device device = ResourceUtils.getResource("integration/device.json", Device.class);
                    device.setName(deviceName);
                    log.info("Creating new device '{}'", deviceName);
                    return tbClient.saveDevice(device);
                });
    }

    private Converter getOrCreateMonitoringConverter() {
        String converterName = "[Monitoring] Default converter";
        return SearchUtils.findByExactName(tbClient::getConverters, converterName, Converter::getName)
                .orElseGet(() -> {
                    Converter converter = ResourceUtils.getResource("integration/converter.json", Converter.class);
                    converter.setName(converterName);
                    log.info("Creating new converter '{}'", converterName);
                    return tbClient.saveConverter(converter);
                });
    }

    private Integration getOrCreateIntegration(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target, ConverterId converterId) {
        String integrationName = integrationEntityName(config, target);
        return SearchUtils.findByExactName(tbClient::getIntegrations, integrationName, Integration::getName)
                .orElseGet(() -> {
                    String routingKey = UUID.randomUUID().toString();
                    Map<String, String> templateParams = config.buildTemplateParams(target, routingKey);
                    // Placeholders are substituted before the text is parsed - a numeric field (the
                    // MQTT port) can only be filled in with a real JSON number this way; parsing
                    // first and formatting configuration.toString() afterwards would leave it a
                    // quoted string.
                    String rawTemplate = ResourceUtils.getResourceAsString(
                            "integration/" + config.getIntegrationType().name().toLowerCase() + "/integration.json");
                    String renderedTemplate = ResourceUtils.substitutePlaceholders(rawTemplate, templateParams, "No template parameter found for key %s");
                    Integration integration = JacksonUtil.fromString(renderedTemplate, Integration.class);

                    integration.setName(integrationName);
                    integration.setDefaultConverterId(converterId);
                    integration.setRoutingKey(routingKey);
                    log.info("Creating new integration '{}'", integrationName);
                    return tbClient.saveIntegration(integration);
                });
    }

}

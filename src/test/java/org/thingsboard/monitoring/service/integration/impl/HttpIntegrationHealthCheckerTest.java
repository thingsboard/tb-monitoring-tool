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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.config.integration.HttpIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.util.RestTemplateUtils;
import org.thingsboard.server.common.data.integration.Integration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Pins down the part of HttpIntegrationHealthChecker this PR changed: the endpoint is read from
// the saved Integration once, in initClient(), and cached - not recomputed on every
// sendTestPayload() call as before. RestTemplateUtils.build is stubbed statically so no real
// RestTemplate/network is involved.
class HttpIntegrationHealthCheckerTest {

    @Test
    void postsTestPayloadToEndpointCachedFromSavedIntegration() throws Exception {
        HttpIntegrationMonitoringConfig config = new HttpIntegrationMonitoringConfig();
        config.setRequestTimeoutMs(4000);
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("http://example.com");
        target.setIntegration(integrationWithEndpoint("http://example.com/api/v1/integrations/http/abc-123"));

        HttpIntegrationHealthChecker checker = new HttpIntegrationHealthChecker(config, target);
        RestTemplate mockRestTemplate = mock(RestTemplate.class);

        try (MockedStatic<RestTemplateUtils> restTemplateUtils = mockStatic(RestTemplateUtils.class)) {
            restTemplateUtils.when(() -> RestTemplateUtils.build(anyInt())).thenReturn(mockRestTemplate);
            checker.initClient();
        }
        checker.sendTestPayload("test-payload");

        verify(mockRestTemplate).postForObject(
                eq("http://example.com/api/v1/integrations/http/abc-123"), eq("test-payload"), eq(String.class));
    }

    @Test
    void secondInitClientCallDoesNotRebuildClient() throws Exception {
        HttpIntegrationMonitoringConfig config = new HttpIntegrationMonitoringConfig();
        config.setRequestTimeoutMs(4000);
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("http://example.com");
        target.setIntegration(integrationWithEndpoint("http://example.com/api/v1/integrations/http/abc-123"));

        HttpIntegrationHealthChecker checker = new HttpIntegrationHealthChecker(config, target);

        try (MockedStatic<RestTemplateUtils> restTemplateUtils = mockStatic(RestTemplateUtils.class)) {
            restTemplateUtils.when(() -> RestTemplateUtils.build(anyInt())).thenReturn(mock(RestTemplate.class));
            checker.initClient();
            checker.initClient();

            restTemplateUtils.verify(() -> RestTemplateUtils.build(anyInt()), times(1));
        }
    }

    private static Integration integrationWithEndpoint(String endpoint) {
        ObjectNode configuration = JacksonUtil.newObjectNode().put("httpEndpoint", endpoint);
        Integration integration = new Integration();
        integration.setConfiguration(configuration);
        return integration;
    }

}

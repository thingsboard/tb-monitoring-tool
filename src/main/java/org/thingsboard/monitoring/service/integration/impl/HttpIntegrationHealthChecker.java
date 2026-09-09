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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.monitoring.config.integration.HttpIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.IntegrationType;
import org.thingsboard.monitoring.service.integration.IntegrationHealthChecker;
import org.thingsboard.monitoring.util.RestTemplateUtils;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class HttpIntegrationHealthChecker extends IntegrationHealthChecker<HttpIntegrationMonitoringConfig> {

    private RestTemplate restTemplate;
    private String endpoint;

    public HttpIntegrationHealthChecker(HttpIntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        super(config, target);
    }

    @Override
    protected void initClient() throws Exception {
        if (restTemplate == null) {
            restTemplate = RestTemplateUtils.build(config.getRequestTimeoutMs());
            endpoint = target.getIntegration().getConfiguration().get("httpEndpoint").asText();
            log.debug("Initialized HTTP client");
        }
    }

    @Override
    protected void sendTestPayload(String payload) throws Exception {
        restTemplate.postForObject(endpoint, payload, String.class);
    }

    @Override
    protected void destroyClient() throws Exception {}

    @Override
    protected IntegrationType getIntegrationType() {
        return IntegrationType.HTTP;
    }

}

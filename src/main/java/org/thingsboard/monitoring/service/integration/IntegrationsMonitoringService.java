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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.service.BaseHealthChecker;
import org.thingsboard.monitoring.service.BaseMonitoringService;

@Service
@RequiredArgsConstructor
@Slf4j
public final class IntegrationsMonitoringService extends BaseMonitoringService<IntegrationMonitoringConfig, IntegrationMonitoringTarget> {

    @Override
    protected BaseHealthChecker<?, ?> createHealthChecker(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        return applicationContext.getBean(config.getIntegrationType().getServiceClass(), config, target);
    }

    @Override
    protected IntegrationMonitoringTarget createTarget(String baseUrl) {
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl(baseUrl);
        return target;
    }

    @Override
    protected String getName() {
        return "integrations check";
    }

}

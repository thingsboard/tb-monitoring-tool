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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.config.DeviceConfig;
import org.thingsboard.monitoring.config.integration.HttpIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.service.PublicSharingService;
import org.thingsboard.monitoring.service.integration.impl.HttpIntegrationHealthChecker;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pins down IntegrationsMonitoringService.init()'s headline behavior: integration checks are
// skipped entirely against a CE target, and proceed normally against a PE target - decided once
// at startup from the detected edition, not left to per-target config.
@ExtendWith(MockitoExtension.class)
class IntegrationsMonitoringServiceTest {

    @Mock
    private PublicSharingService publicSharingService;
    @Mock
    private ApplicationContext applicationContext;

    private IntegrationsMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationsMonitoringService(publicSharingService);
        ReflectionTestUtils.setField(service, "applicationContext", applicationContext);
    }

    @Test
    void skipsInitializationOnCe() {
        when(publicSharingService.isPe()).thenReturn(false);
        setConfigs(List.of(new HttpIntegrationMonitoringConfig()));

        service.init();

        verifyNoInteractions(applicationContext);
    }

    @Test
    void proceedsWithInitializationOnPe_missingTargetBlockProvisionsNothing() {
        // IntegrationMonitoringConfig.getTargets() returns an empty list (not a List.of(null) NPE)
        // when the config's `target:` block is absent - only ever exercised on the CE path above
        // (which returns before getTargets() is reached at all) without this
        when(publicSharingService.isPe()).thenReturn(true);
        setConfigs(List.of(new HttpIntegrationMonitoringConfig()));

        service.init();

        verifyNoInteractions(applicationContext);
    }

    @Test
    void proceedsWithInitializationOnPe() {
        when(publicSharingService.isPe()).thenReturn(true);
        HttpIntegrationMonitoringConfig config = new HttpIntegrationMonitoringConfig();
        IntegrationMonitoringTarget target = new IntegrationMonitoringTarget();
        target.setBaseUrl("http://example.com");
        config.setTarget(target);
        setConfigs(List.of(config));

        HttpIntegrationHealthChecker mockChecker = mock(HttpIntegrationHealthChecker.class);
        when(applicationContext.getBean(eq(HttpIntegrationHealthChecker.class), any(), any())).thenReturn(mockChecker);
        DeviceConfig deviceConfig = new DeviceConfig();
        deviceConfig.setId(UUID.randomUUID().toString());
        doAnswer(inv -> {
            target.setDevice(deviceConfig);
            return null;
        }).when(mockChecker).initialize();

        service.init();

        verify(mockChecker).initialize();
    }

    private void setConfigs(List<?> configs) {
        ReflectionTestUtils.setField(service, "configs", configs);
    }

}

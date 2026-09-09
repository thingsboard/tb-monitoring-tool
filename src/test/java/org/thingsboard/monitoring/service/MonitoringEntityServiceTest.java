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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.util.ResourceUtils;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Covers the public dashboard link building (this PR simplified it from a reflection-based
// TbClient.baseURL read to a plain getter), and dashboard versioning (the largest new logic this
// PR added to this class). The version lives inside Dashboard.configuration rather than as a saved
// attribute - ThingsBoard CE has no support for attribute writes on DASHBOARD entities ("Not
// Implemented!"), only PE does. The rule chain/asset/device-provisioning logic predates this PR and
// isn't covered here. Integration entity provisioning is IntegrationHealthChecker's own concern now
// (it injects IntegrationEntityService directly) - see IntegrationEntityServiceTest.
@ExtendWith(MockitoExtension.class)
class MonitoringEntityServiceTest {

    @Mock
    private TbClient tbClient;
    @Mock
    private PublicSharingService publicSharingService;

    private MonitoringEntityService entityService;

    @BeforeEach
    void setUp() {
        entityService = new MonitoringEntityService(tbClient, publicSharingService);
    }

    @Test
    void dashboardPublicLinkUsesTbClientBaseUrl() {
        DashboardId dashboardId = new DashboardId(UUID.randomUUID());
        entityService.dashboardId = dashboardId;
        when(publicSharingService.getPublicCustomerId(dashboardId)).thenReturn("customer-1");
        when(tbClient.getBaseUrl()).thenReturn("http://example.com:8080");

        String link = entityService.getDashboardPublicLink();

        assertThat(link).isEqualTo("http://example.com:8080/dashboard/" + dashboardId.getId() + "?publicId=customer-1");
    }

    @Test
    void dashboardPublicLinkIsEmptyWhenDashboardIdNotSet() {
        assertThat(entityService.getDashboardPublicLink()).isEmpty();
    }

    private static final String DASHBOARD_TITLE = "[Monitoring] Cloud monitoring";

    // Read from the actual resource rather than hardcoded, so a future version bump in
    // dashboard_cloud_monitoring.json can't silently push these tests down the wrong branch.
    private static int currentDashboardResourceVersion() {
        return ResourceUtils.getResource("dashboard_cloud_monitoring.json").get("version").asInt();
    }

    @Test
    void createsNewDashboardWhenNoneExists() {
        when(tbClient.getTenantDashboards(any(PageLink.class))).thenReturn(PageData.emptyPageData());
        when(tbClient.saveDashboard(any())).thenAnswer(inv -> {
            Dashboard dashboard = inv.getArgument(0);
            dashboard.setId(new DashboardId(UUID.randomUUID()));
            return dashboard;
        });

        Dashboard saved = entityService.getOrCreateMonitoringDashboard();

        assertThat(saved.getTitle()).isEqualTo(DASHBOARD_TITLE);
        assertThat(saved.getConfiguration().get("version").asInt()).isEqualTo(currentDashboardResourceVersion());
        verify(tbClient, never()).saveEntityAttributesV2(any(), any(), any());
    }

    @Test
    void reusesExistingDashboardWhenVersionMatches() {
        Dashboard existing = new Dashboard(new DashboardId(UUID.randomUUID()));
        existing.setTitle(DASHBOARD_TITLE);
        existing.setConfiguration(JacksonUtil.newObjectNode().put("version", currentDashboardResourceVersion()));
        DashboardInfo existingInfo = new DashboardInfo(existing);
        when(tbClient.getTenantDashboards(any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(existingInfo), 1, 1, false));
        when(tbClient.getDashboardById(existing.getId())).thenReturn(Optional.of(existing));

        Dashboard result = entityService.getOrCreateMonitoringDashboard();

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(tbClient, never()).saveDashboard(any());
    }

    @Test
    void reusesExistingDashboardWhenVersionFieldIsAbsent() {
        // Dashboards created before this PR (or on CE, where the version used to be a saved
        // attribute that never worked) have no "version" key in configuration at all - treated as
        // version 0, same as the pre-existing rule chain versioning's default.
        Dashboard existing = new Dashboard(new DashboardId(UUID.randomUUID()));
        existing.setTitle(DASHBOARD_TITLE);
        existing.setConfiguration(JacksonUtil.newObjectNode());
        DashboardInfo existingInfo = new DashboardInfo(existing);
        when(tbClient.getTenantDashboards(any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(existingInfo), 1, 1, false));
        when(tbClient.getDashboardById(existing.getId())).thenReturn(Optional.of(existing));
        when(tbClient.saveDashboard(any())).thenAnswer(inv -> inv.getArgument(0));

        entityService.getOrCreateMonitoringDashboard();

        verify(tbClient).saveDashboard(any());
    }

    @Test
    void updatesExistingDashboardWhenVersionDiffers() {
        Dashboard existing = new Dashboard(new DashboardId(UUID.randomUUID()));
        existing.setTitle(DASHBOARD_TITLE);
        existing.setConfiguration(JacksonUtil.newObjectNode().put("version", currentDashboardResourceVersion() + 1));
        DashboardInfo existingInfo = new DashboardInfo(existing);
        when(tbClient.getTenantDashboards(any(PageLink.class)))
                .thenReturn(new PageData<>(List.of(existingInfo), 1, 1, false));
        when(tbClient.getDashboardById(existing.getId())).thenReturn(Optional.of(existing));
        when(tbClient.saveDashboard(any())).thenAnswer(inv -> inv.getArgument(0));

        Dashboard result = entityService.getOrCreateMonitoringDashboard();

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getTitle()).isEqualTo(DASHBOARD_TITLE);
        assertThat(result.getConfiguration().get("version").asInt()).isEqualTo(currentDashboardResourceVersion());
        verify(tbClient).saveDashboard(any());
        verify(tbClient, never()).saveEntityAttributesV2(any(), any(), any());
    }

}

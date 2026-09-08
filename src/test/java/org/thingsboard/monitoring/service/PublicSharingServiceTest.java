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
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.client.Edition;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ShortCustomerInfo;
import org.thingsboard.server.common.data.ShortEntityView;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.EntityGroupId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicSharingServiceTest {

    @Mock
    private TbClient tbClient;

    private PublicSharingService service;

    @BeforeEach
    void setUp() {
        service = new PublicSharingService(tbClient);
    }

    @Test
    void isPeUsesEditionFromSystemInfoWhenAvailable() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));

        assertThat(service.isPe()).isTrue();
        verify(tbClient, never()).getEntityGroupsByType(any());
    }

    @Test
    void isPeReturnsFalseForCe() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.CE));

        assertThat(service.isPe()).isFalse();
    }

    @Test
    void isPeProbesPeOnlyEndpointWhenTypeIsMissing() {
        when(tbClient.getEdition()).thenReturn(Optional.empty());
        when(tbClient.getEntityGroupsByType(EntityType.CUSTOMER)).thenReturn(List.of());

        assertThat(service.isPe()).isTrue();
    }

    @Test
    void isPeAssumesCeWhenProbeFails() {
        when(tbClient.getEdition()).thenReturn(Optional.empty());
        when(tbClient.getEntityGroupsByType(EntityType.CUSTOMER)).thenThrow(new RuntimeException("404"));

        assertThat(service.isPe()).isFalse();
    }

    @Test
    void isPeCachesDetectedEdition() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));

        service.isPe();
        service.isPe();

        verify(tbClient, times(1)).getEdition();
    }

    @Test
    void makeAssetPublicOnCeAssignsToPublicCustomer() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.CE));
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));

        service.makeAssetPublic(asset);

        verify(tbClient).assignAssetToPublicCustomer(asset.getId());
        verify(tbClient, never()).getEntityGroupInfoByOwnerAndNameAndType(any(), any(), any());
    }

    @Test
    void makeAssetPublicOnPeCreatesGroupAndAddsEntityWhenMissing() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.ASSET), any()))
                .thenReturn(Optional.empty());

        EntityGroupInfo created = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        created.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.saveEntityGroup(any())).thenReturn(created);

        PageData<ShortEntityView> emptyPage = new PageData<>(List.of(), 0, 0, false);
        when(tbClient.getEntities(eq(created.getId()), any(PageLink.class))).thenReturn(emptyPage);

        service.makeAssetPublic(asset);

        verify(tbClient).saveEntityGroup(any(EntityGroup.class));
        verify(tbClient, never()).makeEntityGroupPublic(any());
        verify(tbClient).addEntitiesToEntityGroup(created.getId(), List.of(asset.getId()));
    }

    @Test
    void makeAssetPublicOnPeMakesExistingGroupPublicAndSkipsAlreadyMember() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));

        EntityGroupInfo notYetPublic = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.ASSET), any()))
                .thenReturn(Optional.of(notYetPublic));

        EntityGroupInfo nowPublic = new EntityGroupInfo(notYetPublic.getId());
        nowPublic.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.getEntityGroupById(notYetPublic.getId())).thenReturn(Optional.of(nowPublic));

        PageData<ShortEntityView> pageWithAsset = new PageData<>(List.of(new ShortEntityView(asset.getId())), 1, 1, false);
        when(tbClient.getEntities(eq(notYetPublic.getId()), any(PageLink.class))).thenReturn(pageWithAsset);

        service.makeAssetPublic(asset);

        verify(tbClient).makeEntityGroupPublic(notYetPublic.getId());
        verify(tbClient, never()).saveEntityGroup(any());
        verify(tbClient, never()).addEntitiesToEntityGroup(any(), any());
    }

    @Test
    void makeAssetPublicFindsExistingMemberOnSecondPage() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));

        EntityGroupInfo group = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        group.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.ASSET), any()))
                .thenReturn(Optional.of(group));

        PageData<ShortEntityView> firstPage = new PageData<>(List.of(new ShortEntityView(new AssetId(UUID.randomUUID()))), 2, 2, true);
        PageData<ShortEntityView> secondPage = new PageData<>(List.of(new ShortEntityView(asset.getId())), 2, 2, false);
        when(tbClient.getEntities(eq(group.getId()), any(PageLink.class))).thenReturn(firstPage, secondPage);

        service.makeAssetPublic(asset);

        verify(tbClient, never()).addEntitiesToEntityGroup(any(), any());
    }

    @Test
    void makeAssetPublicAddsMemberWhenNotFoundAcrossMultiplePages() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        Asset asset = new Asset(new AssetId(UUID.randomUUID()));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));

        EntityGroupInfo group = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        group.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.ASSET), any()))
                .thenReturn(Optional.of(group));

        PageData<ShortEntityView> firstPage = new PageData<>(List.of(new ShortEntityView(new AssetId(UUID.randomUUID()))), 2, 2, true);
        PageData<ShortEntityView> secondPage = new PageData<>(List.of(new ShortEntityView(new AssetId(UUID.randomUUID()))), 2, 2, false);
        when(tbClient.getEntities(eq(group.getId()), any(PageLink.class))).thenReturn(firstPage, secondPage);

        service.makeAssetPublic(asset);

        verify(tbClient).addEntitiesToEntityGroup(group.getId(), List.of(asset.getId()));
    }

    @Test
    void makeDashboardPublicOnCeAssignsToPublicCustomer() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.CE));
        Dashboard dashboard = new Dashboard(new DashboardId(UUID.randomUUID()));

        service.makeDashboardPublic(dashboard);

        verify(tbClient).assignDashboardToPublicCustomer(dashboard.getId());
        verify(tbClient, never()).getEntityGroupInfoByOwnerAndNameAndType(any(), any(), any());
    }

    @Test
    void makeDashboardPublicOnPeCreatesGroupAndAddsEntityWhenMissing() {
        // mirrors makeAssetPublicOnPeCreatesGroupAndAddsEntityWhenMissing, exercising the DASHBOARD
        // group type - getPublicCustomerIdPe() later depends on this same group existing
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        Dashboard dashboard = new Dashboard(new DashboardId(UUID.randomUUID()));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.DASHBOARD), any()))
                .thenReturn(Optional.empty());

        EntityGroupInfo created = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        created.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.saveEntityGroup(any())).thenReturn(created);

        PageData<ShortEntityView> emptyPage = new PageData<>(List.of(), 0, 0, false);
        when(tbClient.getEntities(eq(created.getId()), any(PageLink.class))).thenReturn(emptyPage);

        service.makeDashboardPublic(dashboard);

        verify(tbClient).saveEntityGroup(any(EntityGroup.class));
        verify(tbClient).addEntitiesToEntityGroup(created.getId(), List.of(dashboard.getId()));
    }

    @Test
    void getPublicCustomerIdOnPeReadsGroupAdditionalInfo() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));

        UUID publicCustomerId = UUID.randomUUID();
        EntityGroupInfo group = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        group.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true).put("publicCustomerId", publicCustomerId.toString()));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.DASHBOARD), any()))
                .thenReturn(Optional.of(group));

        DashboardId dashboardId = new DashboardId(UUID.randomUUID());
        assertThat(service.getPublicCustomerId(dashboardId)).isEqualTo(publicCustomerId.toString());
    }

    @Test
    void getPublicCustomerIdOnPeReturnsNullWhenGroupHasNoPublicCustomerYet() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.PE));
        User user = new User();
        user.setOwnerId(new CustomerId(UUID.randomUUID()));
        when(tbClient.getUser()).thenReturn(Optional.of(user));

        EntityGroupInfo group = new EntityGroupInfo(new EntityGroupId(UUID.randomUUID()));
        group.setAdditionalInfo(JacksonUtil.newObjectNode().put("isPublic", true));
        when(tbClient.getEntityGroupInfoByOwnerAndNameAndType(eq(user.getOwnerId()), eq(EntityType.DASHBOARD), any()))
                .thenReturn(Optional.of(group));

        assertThat(service.getPublicCustomerId(new DashboardId(UUID.randomUUID()))).isNull();
    }

    @Test
    void getPublicCustomerIdOnCeFiltersAssignedCustomersForPublicOne() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.CE));
        DashboardId dashboardId = new DashboardId(UUID.randomUUID());
        DashboardInfo info = new DashboardInfo(new Dashboard(dashboardId));
        CustomerId publicCustomerId = new CustomerId(UUID.randomUUID());
        info.setAssignedCustomers(Set.of(
                new ShortCustomerInfo(new CustomerId(UUID.randomUUID()), "Regular", false),
                new ShortCustomerInfo(publicCustomerId, "Public", true)
        ));
        when(tbClient.getDashboardInfoById(dashboardId)).thenReturn(Optional.of(info));

        assertThat(service.getPublicCustomerId(dashboardId)).isEqualTo(publicCustomerId.getId().toString());
    }

    @Test
    void getPublicCustomerIdOnCeReturnsNullWhenDashboardNotFound() {
        when(tbClient.getEdition()).thenReturn(Optional.of(Edition.CE));
        DashboardId dashboardId = new DashboardId(UUID.randomUUID());
        when(tbClient.getDashboardInfoById(dashboardId)).thenReturn(Optional.empty());

        assertThat(service.getPublicCustomerId(dashboardId)).isNull();
    }

}

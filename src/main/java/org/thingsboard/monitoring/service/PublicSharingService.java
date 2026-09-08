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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.EntityGroupId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Making an entity public works differently on CE (assign to the built-in public customer) and PE
// (add to a per-type "public" Entity Group) - this is the single place that knows the difference.
@Service
@Slf4j
@RequiredArgsConstructor
public class PublicSharingService {

    private static final int PAGE_SIZE = 100;

    private final TbClient tbClient;

    // Probed once and cached, so a single build monitors both CE and PE targets.
    private Edition edition;

    // Keyed by type, not just for the dashboards group - getPublicCustomerId() for a PE dashboard
    // calls this again right after makeDashboardPublic() already fetched/created it.
    private final Map<EntityType, EntityGroupInfo> publicGroups = new EnumMap<>(EntityType.class);

    public boolean isPe() {
        if (edition == null) {
            edition = tbClient.getEdition()
                    .map(detected -> {
                        log.info("Detected ThingsBoard edition from /api/system/info: {}", detected);
                        return detected;
                    })
                    .orElseGet(this::probeEdition);
        }
        return edition == Edition.PE;
    }

    // Fallback for the unlikely case /api/system/info doesn't return a "type" field.
    private Edition probeEdition() {
        Edition result;
        try {
            tbClient.getEntityGroupsByType(EntityType.CUSTOMER);
            result = Edition.PE;
        } catch (Exception e) {
            result = Edition.CE;
            log.warn("Failed to probe ThingsBoard edition via a PE-only endpoint, assuming CE", e);
        }
        log.info("Detected ThingsBoard edition by probing a PE-only endpoint: {}", result);
        return result;
    }

    public void makeAssetPublic(Asset asset) {
        if (isPe()) {
            addToPublicGroup(asset.getId(), EntityType.ASSET);
        } else {
            tbClient.assignAssetToPublicCustomer(asset.getId());
        }
    }

    public void makeDashboardPublic(Dashboard dashboard) {
        if (isPe()) {
            addToPublicGroup(dashboard.getId(), EntityType.DASHBOARD);
        } else {
            tbClient.assignDashboardToPublicCustomer(dashboard.getId());
        }
    }

    private void addToPublicGroup(EntityId entityId, EntityType type) {
        EntityGroupInfo group = getOrCreatePublicGroup(type);
        if (!isInGroup(group.getId(), entityId)) {
            tbClient.addEntitiesToEntityGroup(group.getId(), List.of(entityId));
        }
    }

    // getGroupEntity() doesn't map "not found" to an empty Optional - it throws a 400 instead, so
    // membership is checked by scanning the group's entities rather than relying on that error shape.
    private boolean isInGroup(EntityGroupId groupId, EntityId entityId) {
        PageLink pageLink = new PageLink(PAGE_SIZE);
        PageData<ShortEntityView> page;
        do {
            page = tbClient.getEntities(groupId, pageLink);
            if (page.getData().stream().map(ShortEntityView::getId).anyMatch(entityId::equals)) {
                return true;
            }
            pageLink = pageLink.nextPageLink();
        } while (page.hasNext());
        return false;
    }

    private EntityGroupInfo getOrCreatePublicGroup(EntityType type) {
        EntityGroupInfo cached = publicGroups.get(type);
        if (cached != null) {
            return cached;
        }
        String groupName = "[Monitoring] Public " + type.name().toLowerCase() + "s";
        EntityId ownerId = tbClient.getUser().map(User::getOwnerId).orElseThrow();
        EntityGroupInfo group = tbClient.getEntityGroupInfoByOwnerAndNameAndType(ownerId, type, groupName)
                .orElseGet(() -> {
                    EntityGroup newGroup = new EntityGroup();
                    newGroup.setName(groupName);
                    newGroup.setType(type);
                    newGroup.setOwnerId(ownerId);
                    log.info("Creating new public entity group '{}'", groupName);
                    return tbClient.saveEntityGroup(newGroup);
                });
        if (!group.isPublic()) {
            tbClient.makeEntityGroupPublic(group.getId());
            group = tbClient.getEntityGroupById(group.getId()).orElse(group);
        }
        publicGroups.put(type, group);
        return group;
    }

    public String getPublicCustomerId(DashboardId dashboardId) {
        return isPe() ? getPublicCustomerIdPe() : getPublicCustomerIdCe(dashboardId);
    }

    private String getPublicCustomerIdCe(DashboardId dashboardId) {
        Optional<DashboardInfo> infoOpt = tbClient.getDashboardInfoById(dashboardId);
        if (infoOpt.isEmpty()) {
            return null;
        }
        Set<ShortCustomerInfo> customers = infoOpt.get().getAssignedCustomers();
        if (customers == null) {
            return null;
        }
        return customers.stream()
                .filter(ShortCustomerInfo::isPublic)
                .map(c -> c.getCustomerId().getId().toString())
                .findFirst().orElse(null);
    }

    private String getPublicCustomerIdPe() {
        EntityGroupInfo group = getOrCreatePublicGroup(EntityType.DASHBOARD);
        JsonNode additionalInfo = group.getAdditionalInfo();
        if (additionalInfo != null && additionalInfo.has("publicCustomerId")) {
            return additionalInfo.get("publicCustomerId").asText();
        }
        return null;
    }

}

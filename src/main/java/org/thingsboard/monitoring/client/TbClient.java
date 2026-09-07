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
package org.thingsboard.monitoring.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DashboardId;

import java.time.Duration;
import java.util.Optional;

@Component
@Slf4j
public class TbClient extends RestClient {

    @Value("${monitoring.rest.username}")
    private String username;
    @Value("${monitoring.rest.password}")
    private String password;

    public TbClient(@Value("${monitoring.rest.base_url}") String baseUrl,
                    @Value("${monitoring.rest.request_timeout_ms}") int requestTimeoutMs) {
        super(new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(requestTimeoutMs))
                .readTimeout(Duration.ofMillis(requestTimeoutMs))
                .build(), baseUrl);
    }

    @PostConstruct
    private void init() {
        logIn();
    }

    public String logIn() {
        login(username, password);
        return getToken();
    }

    // A dedicated "type": "CE"/"PE" field, put there specifically to answer this question - not a
    // heuristic. Requires auth (any role, including CUSTOMER_USER) but that's already established
    // by the time this is called.
    public Optional<JsonNode> getSystemVersionInfo() {
        try {
            JsonNode info = restTemplate.getForObject(baseURL + "/api/system/info", JsonNode.class);
            return Optional.ofNullable(info);
        } catch (Exception e) {
            log.debug("Failed to fetch /api/system/info", e);
            return Optional.empty();
        }
    }

    // CE-only REST call, added directly here for CE targets - see MonitoringEntityService.
    public Optional<Asset> assignAssetToPublicCustomer(AssetId assetId) {
        try {
            ResponseEntity<Asset> asset = restTemplate.postForEntity(baseURL + "/api/customer/public/asset/{assetId}", null, Asset.class, assetId.getId());
            return Optional.ofNullable(asset.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public Optional<Dashboard> assignDashboardToPublicCustomer(DashboardId dashboardId) {
        try {
            ResponseEntity<Dashboard> dashboard = restTemplate.postForEntity(baseURL + "/api/customer/public/dashboard/{dashboardId}", null, Dashboard.class, dashboardId.getId());
            return Optional.ofNullable(dashboard.getBody());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

}

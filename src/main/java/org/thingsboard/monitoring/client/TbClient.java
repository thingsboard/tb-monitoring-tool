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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.thingsboard.monitoring.util.RestTemplateUtils;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DashboardId;

import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class TbClient extends RestClient {

    @Value("${monitoring.rest.username}")
    private String username;
    @Value("${monitoring.rest.password}")
    private String password;

    public TbClient(@Value("${monitoring.rest.base_url}") String baseUrl,
                    @Value("${monitoring.rest.request_timeout_ms}") int requestTimeoutMs) {
        super(RestTemplateUtils.build(requestTimeoutMs), baseUrl);
    }

    @PostConstruct
    private void init() {
        logIn();
    }

    public String logIn() {
        login(username, password);
        return getToken();
    }

    // RestClient.baseURL is protected but has no accessor of its own.
    public String getBaseUrl() {
        return baseURL;
    }

    // A dedicated "type": "CE"/"PE" field, put there specifically to answer this question - not a
    // heuristic. Requires auth (any role, including CUSTOMER_USER) but that's already established
    // by the time this is called. The PE RestClient's getSystemInfo() DTO doesn't carry "type", so
    // the endpoint is hit directly here instead.
    public Optional<Edition> getEdition() {
        try {
            JsonNode info = restTemplate.getForObject(baseURL + "/api/system/info", JsonNode.class);
            String type = info != null ? info.path("type").asText("") : "";
            return type.isEmpty() ? Optional.empty() : Optional.of("PE".equalsIgnoreCase(type) ? Edition.PE : Edition.CE);
        } catch (Exception e) {
            log.debug("Failed to fetch /api/system/info", e);
            return Optional.empty();
        }
    }

    // CE-only REST calls, added directly here for CE targets - see PublicSharingService.
    public void assignAssetToPublicCustomer(AssetId assetId) {
        postForPublicCustomer("/api/customer/public/asset/{id}", assetId.getId());
    }

    public void assignDashboardToPublicCustomer(DashboardId dashboardId) {
        postForPublicCustomer("/api/customer/public/dashboard/{id}", dashboardId.getId());
    }

    private void postForPublicCustomer(String path, UUID id) {
        try {
            restTemplate.postForEntity(baseURL + path, null, Void.class, id);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
        }
    }

}

/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.tools.service.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RuleChainManager {

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    private RestClient restClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void createRuleChainAndSetAsRoot() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);

        setDefaultRuleChainAsRoot();

        deleteAllPreviousTbStatusCheckRuleChains();

        try {
            JsonNode updatedRootRuleChainConfig = objectMapper.readTree(this.getClass().getClassLoader().getResourceAsStream("root_rule_chain.json"));
            RuleChain ruleChain = objectMapper.treeToValue(updatedRootRuleChainConfig.get("ruleChain"), RuleChain.class);
            RuleChainId updatedRuleChainId = saveUpdatedRootRuleChain(ruleChain);
            setRootRuleChain(updatedRuleChainId);

            RuleChainMetaData ruleChainMetaData = objectMapper.treeToValue(updatedRootRuleChainConfig.get("metadata"), RuleChainMetaData.class);
            ruleChainMetaData.setRuleChainId(updatedRuleChainId);
            saveUpdatedRootRuleChainMetadata(ruleChainMetaData);

        } catch (Exception e) {
            log.error("Exception during creation of root rule chain", e);
        }
    }

    private void setRootRuleChain(RuleChainId rootRuleChain) {
        restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain/" + rootRuleChain.getId() + "/root", null, RuleChain.class);
    }

    private RuleChainId saveUpdatedRootRuleChain(RuleChain updatedRuleChain) {
        ResponseEntity<RuleChain> ruleChainResponse = restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain", updatedRuleChain, RuleChain.class);

        return ruleChainResponse.getBody().getId();
    }

    private void saveUpdatedRootRuleChainMetadata(RuleChainMetaData ruleChainMetaData) {
        restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain/metadata",
                        ruleChainMetaData,
                        RuleChainMetaData.class);
    }

    private void deleteAllPreviousTbStatusCheckRuleChains() {
        ResponseEntity<TextPageData<RuleChain>> ruleChains =
                restClient.getRestTemplate().exchange(
                        restUrl + "/api/ruleChains?limit=999&textSearch=TB Status Check Rule Chain",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<TextPageData<RuleChain>>() {
                        });

        List<RuleChainId> ruleChainIds = ruleChains.getBody().getData()
                .stream().map(IdBased::getId).collect(Collectors.toList());

        ruleChainIds.forEach(ruleChainId -> restClient.getRestTemplate().delete(restUrl + "/api/ruleChain/" + ruleChainId.getId()));
    }

    private void setDefaultRuleChainAsRoot() {
        ResponseEntity<TextPageData<RuleChain>> ruleChains =
                restClient.getRestTemplate().exchange(
                        restUrl + "/api/ruleChains?limit=999&textSearch=Root Rule Chain",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<TextPageData<RuleChain>>() {
                        });

        Optional<RuleChain> defaultRuleChain = ruleChains.getBody().getData()
                .stream()
                .findFirst();

        if (!defaultRuleChain.isPresent()) {
            throw new RuntimeException("Root rule chain was not found");
        }

        setRootRuleChain(defaultRuleChain.get().getId());
    }
}

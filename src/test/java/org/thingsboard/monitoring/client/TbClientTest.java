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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.AssetId;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Pins down TbClient.getEdition()'s own parsing/mapping logic - PublicSharingServiceTest only
// stubs this method, so the actual "type" field parsing is otherwise untested. The inherited
// RestClient.restTemplate field is swapped for a plain Mockito mock (rather than binding
// MockRestServiceServer to it) because RestClient wires an auto-login interceptor around it that
// routes through a separate, un-mocked loginRestTemplate - a mock field sidesteps that entirely.
class TbClientTest {

    private static final String SYSTEM_INFO_URL = "http://example.com/api/system/info";

    private TbClient tbClient;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        tbClient = new TbClient("http://example.com", 5000);
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(tbClient, "restTemplate", restTemplate);
    }

    @Test
    void returnsPeForPeType() {
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenReturn(JacksonUtil.toJsonNode("{\"type\": \"PE\"}"));

        assertThat(tbClient.getEdition()).contains(Edition.PE);
    }

    @Test
    void isCaseInsensitiveForPeType() {
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenReturn(JacksonUtil.toJsonNode("{\"type\": \"pe\"}"));

        assertThat(tbClient.getEdition()).contains(Edition.PE);
    }

    @Test
    void returnsCeForAnyNonPeType() {
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenReturn(JacksonUtil.toJsonNode("{\"type\": \"CE\"}"));

        assertThat(tbClient.getEdition()).contains(Edition.CE);
    }

    @Test
    void returnsCeForAnyUnrecognizedType() {
        // "any non-PE type" means exactly that - not just the literal "CE" value
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenReturn(JacksonUtil.toJsonNode("{\"type\": \"FOO\"}"));

        assertThat(tbClient.getEdition()).contains(Edition.CE);
    }

    @Test
    void returnsEmptyWhenTypeFieldIsMissing() {
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenReturn(JacksonUtil.toJsonNode("{}"));

        assertThat(tbClient.getEdition()).isEmpty();
    }

    @Test
    void returnsEmptyWhenRequestFails() {
        when(restTemplate.getForObject(eq(SYSTEM_INFO_URL), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(tbClient.getEdition()).isEmpty();
    }

    @Test
    void assignAssetToPublicCustomer_swallows404() {
        // CE always has a public customer, so a 404 here means the asset itself is gone - the call
        // must not blow up the whole provisioning flow over it
        when(restTemplate.postForEntity(eq("http://example.com/api/customer/public/asset/{id}"), eq(null), eq(Void.class), any(UUID.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatCode(() -> tbClient.assignAssetToPublicCustomer(new AssetId(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void assignAssetToPublicCustomer_rethrowsNon404Errors() {
        when(restTemplate.postForEntity(eq("http://example.com/api/customer/public/asset/{id}"), eq(null), eq(Void.class), any(UUID.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Boom", null, null, null));

        assertThatThrownBy(() -> tbClient.assignAssetToPublicCustomer(new AssetId(UUID.randomUUID())))
                .isInstanceOf(HttpClientErrorException.class);
    }

}

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
package org.thingsboard.monitoring.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Verifies the read timeout passed to build() is actually wired into the returned RestTemplate,
// not just accepted and ignored - a slow-responding local server is used rather than asserting on
// request-factory internals, so this stays valid across whichever ClientHttpRequestFactory
// implementation Spring Boot autodetects on the classpath.
class RestTemplateUtilsTest {

    @Test
    void appliesReadTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            RestTemplate restTemplate = RestTemplateUtils.build(50);

            assertThatThrownBy(() -> restTemplate.getForObject(url, String.class))
                    .isInstanceOf(ResourceAccessException.class);
        } finally {
            server.stop(0);
        }
    }

}

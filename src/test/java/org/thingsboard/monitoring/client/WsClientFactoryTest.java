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

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.data.MonitoredServiceKey;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;
import org.thingsboard.monitoring.service.MonitoringReporter;
import org.thingsboard.monitoring.util.TbStopWatch;

import java.net.InetSocketAddress;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// The only place WS "connect" duration is recorded - exercised against a real embedded WS server
// rather than mocked, since WsClient.connectBlocking() is a real network call this factory owns.
class WsClientFactoryTest {

    private WebSocketServer server;
    private WsClientFactory factory;
    private MonitoringReporter reporter;
    private ProbeMetricsRecorder probeMetricsRecorder;

    @BeforeEach
    void setUp() throws Exception {
        server = new WebSocketServer(new InetSocketAddress("localhost", 0)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
            }

            @Override
            public void onStart() {
            }
        };
        server.start();
        // WebSocketServer binds asynchronously in onStart() - wait for the ephemeral port to be assigned
        while (server.getPort() == 0) {
            Thread.sleep(10);
        }

        reporter = mock(MonitoringReporter.class);
        probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        factory = new WsClientFactory(reporter, probeMetricsRecorder, new TbStopWatch());
        ReflectionTestUtils.setField(factory, "baseUrl", "ws://localhost:" + server.getPort());
        ReflectionTestUtils.setField(factory, "requestTimeoutMs", 3000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    @Test
    void createClient_recordsConnectStageDuration() throws Exception {
        try (WsClient client = factory.createClient("test-token")) {
            assertClientConnected(client);
        }

        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.WS), eq(ProbeMetricsRecorder.ACTION_CONNECT), anyLong());
    }

    @Test
    void createClient_reportsConnectLatency() throws Exception {
        try (WsClient client = factory.createClient("test-token")) {
            assertClientConnected(client);
        }

        verify(reporter).reportLatency(eq("wsConnect"), anyLong());
    }

    private static void assertClientConnected(WsClient client) {
        if (!client.isOpen()) {
            throw new AssertionError("expected the WS client to be connected");
        }
    }

}

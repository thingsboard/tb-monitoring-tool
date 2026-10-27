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
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.client.WsClient;
import org.thingsboard.monitoring.config.MonitoringConfig;
import org.thingsboard.monitoring.config.MonitoringTarget;
import org.thingsboard.monitoring.data.ServiceFailureException;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;
import org.thingsboard.monitoring.util.TbStopWatch;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseHealthCheckerRpcHookTest {

    private static final String INFO = "test-info";
    private static final String RPC_INFO = "test-info RPC";

    private MonitoringReporter reporter;
    private ProbeMetricsRecorder probeMetricsRecorder;
    private WsClient wsClient;
    private TestChecker checker;

    @BeforeEach
    void setUp() {
        reporter = mock(MonitoringReporter.class);
        probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        wsClient = mock(WsClient.class);
        checker = new TestChecker(new TestConfig(), new TestTarget(UUID.randomUUID()));
        ReflectionTestUtils.setField(checker, "info", INFO);
        ReflectionTestUtils.setField(checker, "reporter", reporter);
        ReflectionTestUtils.setField(checker, "probeMetricsRecorder", probeMetricsRecorder);
        ReflectionTestUtils.setField(checker, "stopWatch", new TbStopWatch());
        ReflectionTestUtils.setField(checker, "resultCheckTimeoutMs", 1000);
        when(wsClient.getLatest(any())).thenAnswer(invocation -> checker.capturedTestValue == null
                ? Collections.emptyMap() : Map.of(BaseHealthChecker.TEST_TELEMETRY_KEY, checker.capturedTestValue));
    }

    @Test
    void rpcCheckRunsAfterSuccessfulTelemetryAndReportsOwnRecovery() {
        checker.check(wsClient);

        assertThat(checker.rpcCheckCalled).isTrue();
        verify(reporter).serviceIsOk(INFO);
        verify(reporter).serviceIsOk(RPC_INFO);
    }

    @Test
    void rpcCheckIsNotRunWhenTelemetryPublishFails() {
        checker.failTelemetry = true;

        checker.check(wsClient);

        assertThat(checker.rpcCheckCalled).isFalse();
        verify(reporter).serviceFailure(eq(INFO), any(Throwable.class));
        verify(reporter, never()).serviceIsOk(any());
    }

    @Test
    void rpcCheckIsNotRunWhenWsUpdateMissing() {
        when(wsClient.getLatest(any())).thenReturn(Collections.emptyMap());

        checker.check(wsClient);

        assertThat(checker.rpcCheckCalled).isFalse();
        verify(reporter).serviceFailure(eq(INFO), any(Throwable.class));
        verify(reporter, never()).serviceIsOk(any());
    }

    @Test
    void rpcFailureRoutesThroughDedicatedServiceKeyAndLeavesTransportOk() {
        checker.rpcFailure = new IOException("rpc failed");

        checker.check(wsClient);

        verify(reporter).serviceFailure(eq(RPC_INFO), any(Throwable.class));
        verify(reporter, never()).serviceIsOk(RPC_INFO);
        verify(reporter).serviceIsOk(INFO);
        verify(probeMetricsRecorder).recordProbe(INFO, true);
        verify(probeMetricsRecorder).recordProbe(RPC_INFO, false);
    }

    @Test
    void rpcInitFailureIsReportedUnderRpcKeyAndLeavesTransportOk() {
        checker.rpcInitFailure = new IOException("subscribe failed");

        checker.check(wsClient);

        assertThat(checker.rpcCheckCalled).isFalse();
        verify(reporter).serviceFailure(eq(RPC_INFO), any(Throwable.class));
        verify(reporter).serviceIsOk(INFO);
        verify(probeMetricsRecorder).recordProbe(INFO, true);
        verify(probeMetricsRecorder).recordProbe(RPC_INFO, false);
    }

    @Test
    void rpcProbeIsRecordedOnSuccessAndRemovedWhenTelemetryFails() {
        checker.check(wsClient);
        verify(probeMetricsRecorder).recordProbe(RPC_INFO, true);

        checker.failTelemetry = true;
        checker.check(wsClient);
        verify(probeMetricsRecorder).removeProbe(RPC_INFO, ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE);
    }

    @Test
    void rpcCheckIsSkippedWhenDisabled() {
        checker.rpcInfo = null;

        checker.check(wsClient);

        assertThat(checker.rpcCheckCalled).isFalse();
        assertThat(checker.rpcInitCalled).isFalse();
        verify(reporter).serviceIsOk(INFO);
        verify(probeMetricsRecorder, never()).recordProbe(eq(RPC_INFO), anyBoolean());
    }

    private static final class TestConfig implements MonitoringConfig<TestTarget> {
        @Override
        public List<TestTarget> getTargets() {
            return Collections.emptyList();
        }
    }

    private static final class TestTarget implements MonitoringTarget {
        private final UUID deviceId;

        TestTarget(UUID deviceId) {
            this.deviceId = deviceId;
        }

        @Override public UUID getDeviceId() { return deviceId; }
        @Override public String getBaseUrl() { return "test://target"; }
        @Override public boolean isCheckDomainIps() { return false; }
    }

    private static class TestChecker extends BaseHealthChecker<TestConfig, TestTarget> {

        String capturedTestValue;
        boolean failTelemetry;
        boolean rpcCheckCalled;
        Object rpcInfo = RPC_INFO;
        Throwable rpcFailure;
        Exception rpcInitFailure;
        boolean rpcInitCalled;

        TestChecker(TestConfig config, TestTarget target) {
            super(config, target);
        }

        @Override protected void initialize() { }
        @Override protected void initClient() { }
        @Override protected void destroyClient() { }
        @Override protected Object getInfo() { return INFO; }
        @Override protected String getKey() { return "test"; }
        @Override protected boolean isCfMonitoringEnabled() { return false; }
        @Override protected Object getRpcInfo() { return rpcInfo; }

        @Override
        protected String createTestPayload(String testValue, String telemetryKey) {
            capturedTestValue = testValue;
            return testValue;
        }

        @Override
        protected void sendTestPayload(String payload) throws Exception {
            if (failTelemetry) {
                throw new IOException("telemetry publish failed");
            }
        }

        @Override
        protected void initRpc() throws Exception {
            rpcInitCalled = true;
            if (rpcInitFailure != null) {
                throw rpcInitFailure;
            }
        }

        @Override
        protected void doRpcCheck() {
            rpcCheckCalled = true;
            if (rpcFailure != null) {
                throw new ServiceFailureException(rpcInfo, rpcFailure);
            }
        }
    }

}

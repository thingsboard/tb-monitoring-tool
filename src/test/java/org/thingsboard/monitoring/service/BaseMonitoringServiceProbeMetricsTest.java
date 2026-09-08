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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.client.WsClient;
import org.thingsboard.monitoring.client.WsClientFactory;
import org.thingsboard.monitoring.config.transport.TransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.data.MonitoredServiceKey;
import org.thingsboard.monitoring.data.ServiceFailureException;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;
import org.thingsboard.monitoring.util.TbStopWatch;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityFilter;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.EntityListFilter;
import org.thingsboard.server.common.data.query.TsValue;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BaseMonitoringServiceProbeMetricsTest {

    private TbClient tbClient;
    private WsClientFactory wsClientFactory;
    private MonitoringReporter reporter;
    private ProbeMetricsRecorder probeMetricsRecorder;
    private TestMonitoringService service;
    private WsClient wsClient;
    private BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> healthChecker;

    @BeforeEach
    public void setUp() throws Exception {
        tbClient = mock(TbClient.class);
        wsClientFactory = mock(WsClientFactory.class);
        reporter = mock(MonitoringReporter.class);
        probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        wsClient = mock(WsClient.class);
        when(wsClient.subscribeForTelemetry(any(), any())).thenReturn(wsClient);

        service = new TestMonitoringService();
        ReflectionTestUtils.setField(service, "tbClient", tbClient);
        ReflectionTestUtils.setField(service, "wsClientFactory", wsClientFactory);
        ReflectionTestUtils.setField(service, "reporter", reporter);
        ReflectionTestUtils.setField(service, "probeMetricsRecorder", probeMetricsRecorder);
        ReflectionTestUtils.setField(service, "stopWatch", new TbStopWatch());
        ReflectionTestUtils.setField(service, "dnsResolutionTimeoutMs", 5000L);

        // one stub health checker so runChecks() doesn't short-circuit on the "healthCheckers.isEmpty()" guard;
        // its own check() outcome is irrelevant to this test (it's exercised in BaseHealthCheckerProbeMetricsTest).
        // getTarget() must be stubbed: BaseMonitoringService.check() reads target.isCheckDomainIps() right
        // after invoking it, and an unstubbed null there would NPE out of a "successful" run.
        healthChecker = mock(BaseHealthChecker.class);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(false);
        when(healthChecker.getTarget()).thenReturn(target);
        List<BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget>> healthCheckers =
                (List) ReflectionTestUtils.getField(service, "healthCheckers");
        healthCheckers.add(healthChecker);
    }

    private void givenHealthyLoginAndWs() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenReturn(null);
    }

    private void givenLoginFails() throws Exception {
        when(tbClient.logIn()).thenThrow(new RuntimeException("login failed"));
    }

    private void givenWsConnectFails() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenThrow(new RuntimeException("connect failed"));
    }

    private void givenWsSubscribeFails() throws Exception {
        when(tbClient.logIn()).thenReturn("token");
        when(wsClientFactory.createClient("token")).thenReturn(wsClient);
        when(wsClient.waitForReply()).thenThrow(new IllegalStateException("no reply"));
    }

    @Test
    public void successfulLoginAndWs_recordsBothAsSuccessful() throws Exception {
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(true));
        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(true));
    }

    @Test
    public void successfulLoginAndWs_recordsRequestAndSubscribeStageDurations() throws Exception {
        // WS "connect" duration is no longer recorded here - it moved into WsClientFactory.createClient()
        // itself (see WsClientFactory), reusing the same measurement it already takes for reportLatency
        // instead of BaseMonitoringService measuring the same operation a second time with a raw
        // System.nanoTime() pair. wsClientFactory is a full mock in this test, so that call is never
        // exercised here; it's a 3-line, inspectable change in WsClientFactory itself.
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.LOGIN), eq("request"), anyLong());
        verify(probeMetricsRecorder).recordActionDuration(eq(MonitoredServiceKey.WS), eq("subscribe"), anyLong());
    }

    @Test
    public void successfulLoginAndWs_neverRecordsWsConnectStageDurationDirectly() throws Exception {
        // guards against the redundant System.nanoTime() measurement creeping back into BaseMonitoringService
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder, never()).recordActionDuration(eq(MonitoredServiceKey.WS), eq("connect"), anyLong());
    }

    @Test
    public void loginFailure_recordsLoginFailureAndNeverRecordsWs() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(false));
        verify(probeMetricsRecorder, never()).recordProbe(eq(MonitoredServiceKey.WS), any(Boolean.class));
    }

    @Test
    public void loginFailure_removesWsProbeMetric() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder).removeProbe(eq(MonitoredServiceKey.WS), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void loginFailure_neverRecordsLoginRequestStageDuration() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder, never()).recordActionDuration(eq(MonitoredServiceKey.LOGIN), eq("request"), anyLong());
    }

    @Test
    public void loginFailure_removesLoginRequestStageDuration() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder).removeActionDuration(eq(MonitoredServiceKey.LOGIN), eq("request"));
    }

    @Test
    public void loginFailure_clearsTransportProbeMetrics() throws Exception {
        // transport checks never ran this cycle - their gauges must not keep reporting last cycle's value
        Object transportInfo = new Object();
        when(healthChecker.getCachedInfo()).thenReturn(transportInfo);
        givenLoginFails();

        service.runChecks();

        // scoped to the transport healthChecker's own info - distinct from the WS-key removal from Fix A,
        // which is asserted separately by loginFailure_removesWsProbeMetric
        verify(probeMetricsRecorder, times(1)).removeProbe(transportInfo, ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE);
    }

    @Test
    public void loginFailure_checksTransportAcceptance() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void loginFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        // removeAcceptedProbe (in either Removal mode) must never precede the fallback check on the failure path
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any(), any());
    }

    @Test
    public void wsConnectFailure_recordsWsFailure() throws Exception {
        givenWsConnectFails();

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.LOGIN), eq(true));
        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(false));
    }

    @Test
    public void wsConnectFailure_neverRecordsConnectStageDuration() throws Exception {
        givenWsConnectFails();

        service.runChecks();

        verify(probeMetricsRecorder, never()).recordActionDuration(eq(MonitoredServiceKey.WS), eq("connect"), anyLong());
    }

    @Test
    public void wsConnectFailure_removesConnectAndSubscribeStageDurations() throws Exception {
        // subscribe never even ran this cycle - its last value is stale too
        givenWsConnectFails();

        service.runChecks();

        verify(probeMetricsRecorder).removeActionDuration(eq(MonitoredServiceKey.WS), eq("connect"));
        verify(probeMetricsRecorder).removeActionDuration(eq(MonitoredServiceKey.WS), eq("subscribe"));
    }

    @Test
    public void wsConnectFailure_clearsTransportProbeMetrics() throws Exception {
        givenWsConnectFails();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeProbe(any(), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void wsConnectFailure_checksTransportAcceptance() throws Exception {
        givenWsConnectFails();

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void wsConnectFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        givenWsConnectFails();

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any(), any());
    }

    @Test
    public void wsSubscribeFailure_recordsWsFailure() throws Exception {
        givenWsSubscribeFails();

        service.runChecks();

        verify(probeMetricsRecorder).recordProbe(eq(MonitoredServiceKey.WS), eq(false));
    }

    @Test
    public void wsSubscribeFailure_removesSubscribeStageDurationButNeverConnect() throws Exception {
        // connect already succeeded this cycle (it's how we got here) - only "subscribe" must go away
        givenWsSubscribeFails();

        service.runChecks();

        verify(probeMetricsRecorder).removeActionDuration(eq(MonitoredServiceKey.WS), eq("subscribe"));
        verify(probeMetricsRecorder, never()).removeActionDuration(eq(MonitoredServiceKey.WS), eq("connect"));
    }

    @Test
    public void wsSubscribeFailure_clearsTransportProbeMetrics() throws Exception {
        givenWsSubscribeFails();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeProbe(any(), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void wsSubscribeFailure_checksTransportAcceptance() throws Exception {
        givenWsSubscribeFails();

        service.runChecks();

        verify(healthChecker).checkAccepted();
    }

    @Test
    public void wsSubscribeFailure_neverRemovesAcceptedProbeBeforeFallbackRuns() throws Exception {
        givenWsSubscribeFails();

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeAcceptedProbe(any(), any());
    }

    @Test
    public void successfulRun_neverClearsTransportProbeMetrics() throws Exception {
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder, never()).removeProbe(any(), any());
    }

    @Test
    public void successfulRun_neverChecksTransportAcceptance() throws Exception {
        // WS is healthy, so E2E already covers this target - checkAccepted() firing too would double-send
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(healthChecker, never()).checkAccepted();
    }

    @Test
    public void successfulRun_removesAcceptedProbeForEachHealthChecker() throws Exception {
        // fresh E2E data means any stale accepted-fallback value must be cleared, not frozen forever
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(any(), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void successfulRun_removesAcceptedProbeForAssociatesToo() throws Exception {
        // associates get their own kind="accepted" gauge during an outage too - recovery must clear it
        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> associate =
                mock(BaseHealthChecker.class);
        Object associateInfo = new Object();
        when(associate.getCachedInfo()).thenReturn(associateInfo);
        when(healthChecker.getAssociates()).thenReturn(java.util.Map.of("associate-url", associate));

        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(associateInfo, ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE);
    }

    @Test
    public void unexpectedServiceFailureMidLoop_alsoClearsAcceptedProbeMetrics() throws Exception {
        // caught by the outer handler, not the 3 known branches - must still clear kind="accepted"
        givenHealthyLoginAndWs();
        doThrow(new ServiceFailureException(MonitoredServiceKey.GENERAL, new RuntimeException("boom")))
                .when(healthChecker).check(any());

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(any(), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void unexpectedThrowableMidLoop_alsoClearsAcceptedProbeMetrics() throws Exception {
        givenHealthyLoginAndWs();
        doThrow(new RuntimeException("boom")).when(healthChecker).check(any());

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).removeAcceptedProbe(any(), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
    }

    @Test
    public void reconciliationFailureAfterSuccessfulCheck_doesNotClearThatTargetsMetrics() throws Exception {
        // healthChecker.check() completes normally, so its finally block already recorded fresh
        // probe_success/probe_duration_ms for this cycle. The domain-IP-associate reconciliation
        // that runs afterward (only when isCheckDomainIps() is true) then fails - here because the
        // configured host can never resolve (RFC 2606 reserves the ".invalid" TLD for exactly this).
        // That late, unrelated bookkeeping failure must not wipe the metrics check() already recorded.
        Object firstInfo = new Object();
        when(healthChecker.getCachedInfo()).thenReturn(firstInfo);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(true);
        target.setBaseUrl("tcp://this-host-does-not-resolve.invalid:1883");
        when(healthChecker.getTarget()).thenReturn(target);

        givenHealthyLoginAndWs();

        assertDoesNotThrow(() -> service.runChecks());

        verify(probeMetricsRecorder, never()).removeProbe(eq(firstInfo), any());
    }

    @Test
    public void reconciliationFailure_reportsFailureUnderDedicatedKey_notGeneralOrTargetInfo() throws Exception {
        // regression test: a persistent DNS failure here used to be silently downgraded to a
        // log.warn with no alert at all. It must now alert, but under its own key - not GENERAL
        // (whose serviceIsOk() still fires at the end of this same successful cycle, which would
        // immediately flap the failure back to "recovered") and not the target's own info (which
        // would make a bookkeeping failure look like the probe itself failed).
        Object firstInfo = new Object();
        when(healthChecker.getCachedInfo()).thenReturn(firstInfo);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(true);
        target.setBaseUrl("tcp://this-host-does-not-resolve.invalid:1883");
        when(healthChecker.getTarget()).thenReturn(target);

        givenHealthyLoginAndWs();

        service.runChecks();

        verify(reporter).serviceFailure(argThat(key -> key.toString().equals(firstInfo + " (DNS)")), any());
        verify(reporter, never()).serviceFailure(eq(MonitoredServiceKey.GENERAL), any());
        verify(reporter, never()).serviceFailure(eq(firstInfo), any());
    }

    @Test
    public void reconciliationSuccess_reportsServiceIsOkUnderDedicatedKey() throws Exception {
        Object firstInfo = new Object();
        when(healthChecker.getCachedInfo()).thenReturn(firstInfo);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(true);
        target.setBaseUrl("tcp://127.0.0.1:1883"); // IP literal - deterministic, no real DNS lookup
        when(healthChecker.getTarget()).thenReturn(target);

        // pre-populate the associate the resolution will find, so reconcileAssociates() sees
        // nothing new/retired and completes without needing to create a real health checker
        // (createHealthChecker() isn't exercised in this fixture - see TestMonitoringService below)
        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> existingAssociate = mock(BaseHealthChecker.class);
        Map<String, BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget>> associates = new java.util.HashMap<>();
        associates.put("tcp://127.0.0.1:1883", existingAssociate);
        when(healthChecker.getAssociates()).thenReturn(associates);

        givenHealthyLoginAndWs();

        service.runChecks();

        verify(reporter).serviceIsOk(argThat(key -> key.toString().equals(firstInfo + " (DNS)")));
    }

    @Test
    public void unexpectedThrowableMidLoop_doesNotClearAlreadyCheckedTargets() throws Exception {
        // a SECOND checker's check() throws directly - the first checker already completed and
        // recorded fresh data this cycle (checkedCount was incremented past it) and must survive
        Object firstInfo = new Object();
        when(healthChecker.getCachedInfo()).thenReturn(firstInfo);

        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> secondChecker =
                mock(BaseHealthChecker.class);
        TransportMonitoringTarget secondTarget = new TransportMonitoringTarget();
        secondTarget.setCheckDomainIps(false);
        when(secondChecker.getTarget()).thenReturn(secondTarget);
        Object secondInfo = new Object();
        when(secondChecker.getCachedInfo()).thenReturn(secondInfo);
        doThrow(new RuntimeException("boom")).when(secondChecker).check(any());
        List<BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget>> healthCheckers =
                (List) ReflectionTestUtils.getField(service, "healthCheckers");
        healthCheckers.add(secondChecker);

        givenHealthyLoginAndWs();

        assertDoesNotThrow(() -> service.runChecks());

        verify(probeMetricsRecorder, never()).removeProbe(firstInfo, ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE);
        verify(probeMetricsRecorder, times(1)).removeProbe(secondInfo, ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE);
    }

    @Test
    public void reconcileAssociates_decommissionedAssociate_removesItsMetricsUnconditionally() throws Exception {
        // reconcileAssociates() must call removeProbe/removeAcceptedProbe with Removal.PERMANENT, not
        // STALE_THIS_CYCLE: a decommissioned associate can have completed its own check() (via the
        // real BaseHealthChecker, covered separately by BaseHealthCheckerProbeMetricsTest) and
        // recorded fresh data earlier this very cycle - its metric must still be removed for good,
        // not silently kept alive by the same-cycle freshness guard that STALE_THIS_CYCLE applies.
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setCheckDomainIps(true);
        target.setBaseUrl("tcp://127.0.0.1:1883"); // IP literal - deterministic, no real DNS lookup
        target.setDevice(new org.thingsboard.monitoring.config.DeviceConfig()); // avoids an
        // unrelated NPE in the pre-existing stopHealthChecker(healthChecker) call (out of scope here)
        when(healthChecker.getTarget()).thenReturn(target);

        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> current =
                mock(BaseHealthChecker.class);
        BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget> decommissioned =
                mock(BaseHealthChecker.class);
        Object decommissionedInfo = new Object();
        when(decommissioned.getCachedInfo()).thenReturn(decommissionedInfo);
        // stopHealthChecker() reads getTarget().getDeviceId() on whichever checker it's given - now that
        // it's fixed to stop the retired ASSOCIATE (not the parent healthChecker), that's decommissioned's
        // own target, so it needs the same device stub healthChecker's target got above.
        TransportMonitoringTarget decommissionedTarget = new TransportMonitoringTarget();
        decommissionedTarget.setDevice(new org.thingsboard.monitoring.config.DeviceConfig());
        when(decommissioned.getTarget()).thenReturn(decommissionedTarget);

        java.util.Map<String, BaseHealthChecker<TransportMonitoringConfig, TransportMonitoringTarget>> associates =
                new java.util.HashMap<>();
        associates.put("tcp://127.0.0.1:1883", current); // still resolves - untouched by reconciliation
        associates.put("old-decommissioned-ip", decommissioned); // no longer resolves - must be removed
        when(healthChecker.getAssociates()).thenReturn(associates);

        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder).removeProbe(eq(decommissionedInfo), eq(ProbeMetricsRecorder.Removal.PERMANENT));
        verify(probeMetricsRecorder).removeAcceptedProbe(eq(decommissionedInfo), eq(ProbeMetricsRecorder.Removal.PERMANENT));
        // removeAcceptedProbe(decommissionedInfo, STALE_THIS_CYCLE) legitimately also fires here, from the
        // unconditional per-cycle accepted-fallback sweep over all associates (see
        // successfulRun_removesAcceptedProbeForAssociatesToo) - unrelated to decommissioning, so it's
        // not asserted against here. removeProbe(..., STALE_THIS_CYCLE) never applies to an associate on
        // a successful run though, so that one is safe to assert against.
        verify(probeMetricsRecorder, never()).removeProbe(eq(decommissionedInfo), eq(ProbeMetricsRecorder.Removal.STALE_THIS_CYCLE));
        // the fix under test: stopHealthChecker()-equivalent behavior (destroyClient()) must be invoked
        // on the retired ASSOCIATE, never on the parent healthChecker or the still-current associate
        verify(decommissioned).destroyClient();
        verify(current, never()).destroyClient();
        verify(healthChecker, never()).destroyClient();
    }

    @Test
    public void runChecks_alwaysRecordsHeartbeatOnce_regardlessOfOutcome() throws Exception {
        givenHealthyLoginAndWs();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).recordHeartbeat();
    }

    @Test
    public void runChecks_loginFailure_stillRecordsHeartbeatOnce() throws Exception {
        givenLoginFails();

        service.runChecks();

        verify(probeMetricsRecorder, times(1)).recordHeartbeat();
    }

    @Test
    public void runChecks_startsCycleBeforeAnyRecordOrRemoveCall() throws Exception {
        // startCycle() resets the collision-protection bookkeeping - it must run before any
        // recordProbe/removeProbe call this cycle, on every outcome, not just the successful path.
        // Checked against removeActionDuration(LOGIN, request) specifically because it's the
        // EARLIEST probeMetricsRecorder call in this failure path - recordProbe(LOGIN, false) runs
        // last (in the finally block), so verifying against it alone wouldn't catch startCycle()
        // being moved to anywhere before that point.
        givenLoginFails();

        service.runChecks();

        InOrder inOrder = inOrder(probeMetricsRecorder);
        inOrder.verify(probeMetricsRecorder).startCycle();
        inOrder.verify(probeMetricsRecorder).removeActionDuration(MonitoredServiceKey.LOGIN, ProbeMetricsRecorder.ACTION_REQUEST);
        inOrder.verify(probeMetricsRecorder).recordProbe(MonitoredServiceKey.LOGIN, false);
    }


    @Test
    public void checkEdqs_queriesExactlyTheMonitoredDevices_notEveryDeviceInTenant() throws Exception {
        // regression test: checkEdqs() used to query an EntityTypeFilter over every device in the
        // tenant (paginated), rather than an EntityListFilter scoped to just the devices this
        // instance actually monitors - wasteful on a tenant not dedicated to monitoring.
        ReflectionTestUtils.setField(service, "checkEdqs", true);
        UUID device1 = UUID.randomUUID();
        UUID device2 = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "devices", new LinkedList<>(List.of(device1, device2)));

        PageData<EntityData> result = new PageData<>(List.of(entityDataFor(device1), entityDataFor(device2)), 1, 2, false);
        when(tbClient.findEntityDataByQuery(any())).thenReturn(result);

        givenHealthyLoginAndWs();

        service.runChecks();

        ArgumentCaptor<EntityDataQuery> queryCaptor = ArgumentCaptor.forClass(EntityDataQuery.class);
        verify(tbClient).findEntityDataByQuery(queryCaptor.capture());
        EntityFilter filter = queryCaptor.getValue().getEntityFilter();
        assertThat(filter).isInstanceOf(EntityListFilter.class);
        assertThat(((EntityListFilter) filter).getEntityList())
                .containsExactlyInAnyOrder(device1.toString(), device2.toString());
        verify(reporter, never()).serviceFailure(eq(MonitoredServiceKey.EDQS), any());
        verify(reporter).serviceIsOk(MonitoredServiceKey.EDQS);
    }

    @Test
    public void checkEdqs_deviceMissingFromResponse_reportsFailure() throws Exception {
        ReflectionTestUtils.setField(service, "checkEdqs", true);
        UUID presentDevice = UUID.randomUUID();
        UUID missingDevice = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "devices", new LinkedList<>(List.of(presentDevice, missingDevice)));

        PageData<EntityData> result = new PageData<>(List.of(entityDataFor(presentDevice)), 1, 1, false);
        when(tbClient.findEntityDataByQuery(any())).thenReturn(result);

        givenHealthyLoginAndWs();

        service.runChecks();

        verify(reporter).serviceFailure(eq(MonitoredServiceKey.EDQS), any());
    }

    private static EntityData entityDataFor(UUID deviceId) {
        Map<EntityKeyType, Map<String, TsValue>> latest = new HashMap<>();
        latest.put(EntityKeyType.ENTITY_FIELD, Map.of("name", new TsValue(0, "device"), "type", new TsValue(0, "default")));
        latest.put(EntityKeyType.TIME_SERIES, Map.of(BaseHealthChecker.TEST_TELEMETRY_KEY, new TsValue(0, "value")));
        return new EntityData(new DeviceId(deviceId), true, true, latest, null);
    }

    @Test
    public void getAssociatedUrls_resolvesIpLiteral_returnsSameAddress() {
        // sanity check that wrapping the DNS call in a bounded CompletableFuture (the timeout fix)
        // didn't change the successful-resolution result
        Set<String> urls = (Set<String>) ReflectionTestUtils.invokeMethod(service, "getAssociatedUrls", "tcp://127.0.0.1:1883");

        assertThat(urls).containsExactly("tcp://127.0.0.1:1883");
    }

    @Test
    public void getAssociatedUrls_unresolvableHost_failsInsteadOfHanging() {
        // RFC 2606 reserves the .invalid TLD for exactly this - always fails to resolve, fast
        assertThrows(RuntimeException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "getAssociatedUrls", "tcp://this-host-does-not-resolve.invalid:1883"));
    }

    @Test
    public void resolveWithTimeout_slowResolver_timesOutInsteadOfWaitingForever() {
        // exercises the actual timeout branch (not just "eventually fails") against a deliberately
        // slow supplier, so this doesn't depend on a real hung DNS resolver to verify
        long start = System.currentTimeMillis();

        assertThrows(ServiceFailureException.class, () -> BaseMonitoringService.resolveWithTimeout("slow-host", 50, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new java.net.InetAddress[0];
        }));

        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    }

    @Test
    public void resolveWithTimeout_fastResolver_returnsResultUnchanged() {
        java.net.InetAddress[] resolved = BaseMonitoringService.resolveWithTimeout("fast-host", 5000, () -> new java.net.InetAddress[0]);

        assertThat(resolved).isEmpty();
    }

    private static class TestMonitoringService extends BaseMonitoringService<TransportMonitoringConfig, TransportMonitoringTarget> {
        @Override
        protected BaseHealthChecker<?, ?> createHealthChecker(TransportMonitoringConfig config, TransportMonitoringTarget target) {
            throw new UnsupportedOperationException("not exercised in this test");
        }

        @Override
        protected TransportMonitoringTarget createTarget(String baseUrl) {
            TransportMonitoringTarget target = new TransportMonitoringTarget();
            target.setBaseUrl(baseUrl);
            return target;
        }

        @Override
        protected String getName() {
            return "test";
        }
    }

}

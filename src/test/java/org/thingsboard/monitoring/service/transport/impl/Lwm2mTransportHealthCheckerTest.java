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
package org.thingsboard.monitoring.service.transport.impl;

import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.resource.ObjectEnabler;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.monitoring.client.Lwm2mClient;
import org.thingsboard.monitoring.config.DeviceConfig;
import org.thingsboard.monitoring.config.transport.Lwm2mTransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.metrics.ProbeMetricsRecorder;
import org.thingsboard.monitoring.service.MonitoringReporter;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MDeviceCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.PSKClientCredential;
import org.thingsboard.server.common.data.security.DeviceCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.leshan.core.LwM2mId.SECURITY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class Lwm2mTransportHealthCheckerTest {

    @Test
    public void checkAccepted_isNoOp_neverRecordsAcceptedProbeOrReports() {
        // checkAccepted() is a no-op for LwM2M (see override) - must never touch the metric or alerting
        ProbeMetricsRecorder probeMetricsRecorder = mock(ProbeMetricsRecorder.class);
        MonitoringReporter reporter = mock(MonitoringReporter.class);
        Lwm2mTransportHealthChecker checker = new Lwm2mTransportHealthChecker(
                new Lwm2mTransportMonitoringConfig(), new TransportMonitoringTarget());
        ReflectionTestUtils.setField(checker, "probeMetricsRecorder", probeMetricsRecorder);
        ReflectionTestUtils.setField(checker, "reporter", reporter);

        checker.checkAccepted();

        verifyNoInteractions(probeMetricsRecorder);
        verifyNoInteractions(reporter);
    }

    @Test
    public void initClient_usesPskFromDeviceCredentials() throws Exception {
        PSKClientCredential psk = new PSKClientCredential();
        psk.setEndpoint("endpoint1");
        psk.setIdentity("endpoint1");
        psk.setKey("00112233445566778899aabbccddeeff");
        LwM2MDeviceCredentials lwm2mCredentials = new LwM2MDeviceCredentials();
        lwm2mCredentials.setClient(psk);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsId("endpoint1");
        credentials.setCredentialsValue(JacksonUtil.toString(lwm2mCredentials));
        DeviceConfig device = new DeviceConfig();
        device.setCredentials(credentials);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setBaseUrl("coaps://127.0.0.1:5686");
        target.setDevice(device);
        Lwm2mTransportHealthChecker checker = new Lwm2mTransportHealthChecker(new Lwm2mTransportMonitoringConfig(), target);

        try {
            checker.initClient();

            Lwm2mClient client = (Lwm2mClient) ReflectionTestUtils.getField(checker, "lwm2mClient");
            Security security = (Security) ((ObjectEnabler) client.getLeshanClient().getObjectTree().getObjectEnabler(SECURITY)).getInstance(0);
            assertThat(((LwM2mResource) security.read(null, 2).getContent()).getValue()).isEqualTo(0L); // security mode: PSK
            assertThat(((LwM2mResource) security.read(null, 3).getContent()).getValue()).isEqualTo("endpoint1".getBytes());
        } finally {
            checker.destroyClient();
        }
    }

}

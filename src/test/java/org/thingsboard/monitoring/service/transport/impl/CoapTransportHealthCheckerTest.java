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

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.x509.SingleCertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.monitoring.config.DeviceConfig;
import org.thingsboard.monitoring.config.transport.CoapTransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.server.common.data.security.DeviceCredentials;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CoapTransportHealthCheckerTest {

    private static final String TOKEN = "test-token";

    @TempDir
    Path tempDir;

    private CoapServer server;
    private CoapTransportHealthChecker checker;
    private volatile String receivedPayload;

    @AfterEach
    void tearDown() throws Exception {
        if (checker != null) {
            checker.destroyClient();
        }
        if (server != null) {
            server.destroy();
        }
        System.clearProperty("javax.net.ssl.trustStore");
        System.clearProperty("javax.net.ssl.trustStorePassword");
    }

    @ParameterizedTest
    // SHA384withECDSA is what e.g. Let's Encrypt ECDSA chains are signed with
    @ValueSource(strings = {"SHA256withECDSA", "SHA384withECDSA", "SHA256withRSA", "SHA384withRSA", "SHA512withRSA"})
    void coaps_postsOverDtls_whenServerCertIsTrusted(String signatureAlgorithm) throws Exception {
        X509Certificate cert = startDtlsServer(signatureAlgorithm, "localhost");
        trustOnly(cert);
        checker = checker("coaps://localhost:" + serverPort(), 3000);

        checker.initClient();
        checker.sendTestPayload("{\"testData\":\"1\"}");

        assertThat(receivedPayload).isEqualTo("{\"testData\":\"1\"}");
    }

    @Test
    void coaps_failsHandshake_whenServerCertIsNotTrusted() throws Exception {
        startDtlsServer("SHA256withECDSA", "localhost");
        checker = checker("coaps://localhost:" + serverPort(), 3000);

        checker.initClient();

        assertThatThrownBy(() -> checker.sendTestPayload("{}")).hasStackTraceContaining("HandshakeException");
        assertThat(receivedPayload).isNull();
    }

    @Test
    void coaps_failsHandshake_whenServerCertHostnameDoesNotMatch() throws Exception {
        trustOnly(startDtlsServer("SHA256withECDSA", "other.example.com"));
        checker = checker("coaps://localhost:" + serverPort(), 3000);

        checker.initClient();

        assertThatThrownBy(() -> checker.sendTestPayload("{}")).hasStackTraceContaining("doesn't match");
        assertThat(receivedPayload).isNull();
    }

    // e.g. Temurin's cacerts has P-521 roots
    @Test
    void coaps_initClient_whenTruststoreHasP521Cert() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(521);
        trustOnly(selfSignedCert(generator.generateKeyPair(), "SHA512withECDSA", "p521.example.com"));
        checker = checker("coaps://localhost:5684", 3000);

        checker.initClient();

        assertThat(ReflectionTestUtils.getField(checker, "coapClient")).isNotNull();
    }

    @Test
    void noResponse_failsWithTimeoutAndResetsClient() throws Exception {
        int unusedPort;
        try (DatagramSocket socket = new DatagramSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        checker = checker("coaps://localhost:" + unusedPort, 300);

        checker.initClient();

        assertThatThrownBy(() -> checker.sendTestPayload("{}"))
                .isInstanceOf(IOException.class)
                .hasMessage("No CoAP response within 300 ms");
        assertThat(ReflectionTestUtils.getField(checker, "coapClient")).isNull();
    }

    private static CoapTransportHealthChecker checker(String baseUrl, int requestTimeoutMs) {
        CoapTransportMonitoringConfig config = new CoapTransportMonitoringConfig();
        config.setRequestTimeoutMs(requestTimeoutMs);
        TransportMonitoringTarget target = new TransportMonitoringTarget();
        target.setBaseUrl(baseUrl);
        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsId(TOKEN);
        DeviceConfig device = new DeviceConfig();
        device.setCredentials(credentials);
        target.setDevice(device);
        return new CoapTransportHealthChecker(config, target);
    }

    // mirrors ThingsBoard's CoAP DTLS listener: server cert only, client cert optional (auth is the access token)
    private X509Certificate startDtlsServer(String signatureAlgorithm, String dnsName) throws Exception {
        String keyAlgorithm = signatureAlgorithm.endsWith("ECDSA") ? "EC" : "RSA";
        KeyPairGenerator generator = KeyPairGenerator.getInstance(keyAlgorithm);
        generator.initialize("EC".equals(keyAlgorithm) ? 256 : 2048);
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate cert = selfSignedCert(keyPair, signatureAlgorithm, dnsName);

        Configuration configuration = new Configuration();
        configuration.set(DtlsConfig.DTLS_ROLE, DtlsConfig.DtlsRole.SERVER_ONLY);
        configuration.set(DtlsConfig.DTLS_CLIENT_AUTHENTICATION_MODE, CertificateAuthenticationMode.WANTED);
        DtlsConnectorConfig dtlsConfig = DtlsConnectorConfig.builder(configuration)
                .setAddress(new InetSocketAddress("127.0.0.1", 0))
                .setCertificateIdentityProvider(new SingleCertificateProvider(keyPair.getPrivate(), new X509Certificate[]{cert}))
                .setAdvancedCertificateVerifier(StaticNewAdvancedCertificateVerifier.builder().setTrustAllCertificates().build())
                .build();
        server = new CoapServer(configuration);
        server.addEndpoint(new CoapEndpoint.Builder().setConfiguration(configuration).setConnector(new DTLSConnector(dtlsConfig)).build());
        server.add(new CoapResource("api").add(new CoapResource("v1").add(new CoapResource(TOKEN).add(new CoapResource("telemetry") {
            @Override
            public void handlePOST(CoapExchange exchange) {
                receivedPayload = exchange.getRequestText();
                exchange.respond(CoAP.ResponseCode.CREATED);
            }
        }))));
        server.start();
        return cert;
    }

    private static X509Certificate selfSignedCert(KeyPair keyPair, String signatureAlgorithm, String dnsName) throws Exception {
        X500Name name = new X500Name("CN=" + dnsName);
        Instant now = Instant.now();
        return new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(now.minus(Duration.ofDays(1))),
                        Date.from(now.plus(Duration.ofDays(1))), name, keyPair.getPublic())
                        .addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)))
                        .build(new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate())));
    }

    private int serverPort() {
        return server.getEndpoints().get(0).getAddress().getPort();
    }

    private void trustOnly(X509Certificate cert) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("server", cert);
        Path file = tempDir.resolve("truststore.p12");
        try (FileOutputStream out = new FileOutputStream(file.toFile())) {
            trustStore.store(out, "changeit".toCharArray());
        }
        System.setProperty("javax.net.ssl.trustStore", file.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    }

}

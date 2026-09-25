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

import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.SystemConfig;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.CertificateKeyAlgorithm;
import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.thingsboard.monitoring.config.transport.CoapTransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.config.transport.TransportType;
import org.thingsboard.monitoring.service.transport.TransportHealthChecker;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Slf4j
public class CoapTransportHealthChecker extends TransportHealthChecker<CoapTransportMonitoringConfig> {

    static {
        SystemConfig.register();
        CoapConfig.register();
        DtlsConfig.register();
    }

    private CoapClient coapClient;

    protected CoapTransportHealthChecker(CoapTransportMonitoringConfig config, TransportMonitoringTarget target) {
        super(config, target);
    }

    @Override
    protected void initClient() throws Exception {
        if (coapClient == null) {
            String accessToken = target.getDevice().getCredentials().getCredentialsId();
            String uri = target.getBaseUrl() + "/api/v1/" + accessToken + "/telemetry";
            coapClient = new CoapClient(uri);
            if (CoAP.COAP_SECURE_URI_SCHEME.equalsIgnoreCase(URI.create(uri).getScheme())) {
                coapClient.setEndpoint(createDtlsEndpoint());
            }
            coapClient.setTimeout((long) config.getRequestTimeoutMs());
            log.debug("Initialized CoAP client for URI {}", uri);
        }
    }

    @Override
    protected void sendTestPayload(String payload) throws Exception {
        CoapResponse response = coapClient.post(payload, MediaTypeRegistry.APPLICATION_JSON);
        if (response == null) {
            // start over next time - a DTLS session the server lost (restart, NAT rebinding) never recovers on its own
            destroyClient();
            throw new IOException("No CoAP response within " + config.getRequestTimeoutMs() + " ms");
        }
        CoAP.ResponseCode code = response.getCode();
        if (code.codeClass != CoAP.CodeClass.SUCCESS_RESPONSE.value) {
            throw new IOException("COAP client didn't receive success response from transport");
        }
    }

    @Override
    protected void destroyClient() throws Exception {
        if (coapClient != null) {
            coapClient.shutdown();
            // only set for coaps - the plain coap one is Californium's shared default endpoint
            if (coapClient.getEndpoint() != null) {
                coapClient.getEndpoint().destroy();
            }
            coapClient = null;
            log.info("Disconnected CoAP client");
        }
    }

    @Override
    protected TransportType getTransportType() {
        return TransportType.COAP;
    }

    // server cert is checked the same way as by the HTTPS/MQTTS checks - against the JVM default truststore
    // (-Djavax.net.ssl.trustStore to override), including the hostname
    private static Endpoint createDtlsEndpoint() throws GeneralSecurityException {
        Configuration configuration = new Configuration();
        configuration.set(DtlsConfig.DTLS_ROLE, DtlsConfig.DtlsRole.CLIENT_ONLY);
        configuration.setAsList(DtlsConfig.DTLS_CERTIFICATE_KEY_ALGORITHMS, CertificateKeyAlgorithm.EC, CertificateKeyAlgorithm.RSA);
        // Californium offers only SHA256 by default, and the server aborts the handshake if any cert in its chain
        // is signed otherwise (e.g. Let's Encrypt ECDSA chains are SHA384withECDSA)
        configuration.setAsListFromText(DtlsConfig.DTLS_SIGNATURE_AND_HASH_ALGORITHMS,
                "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "SHA256withRSA", "SHA384withRSA", "SHA512withRSA");
        // curves of all trusted keys get enabled, and the JVM truststore may hold P-521 roots, which Californium rejects by default
        configuration.set(DtlsConfig.DTLS_RECOMMENDED_CURVES_ONLY, false);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        X509Certificate[] trustedCertificates = ((X509TrustManager) trustManagerFactory.getTrustManagers()[0]).getAcceptedIssuers();
        DtlsConnectorConfig dtlsConfig = DtlsConnectorConfig.builder(configuration)
                .setAdvancedCertificateVerifier(StaticNewAdvancedCertificateVerifier.builder()
                        .setTrustedCertificates(trustedCertificates)
                        .build())
                .build();
        return new CoapEndpoint.Builder()
                .setConfiguration(configuration)
                .setConnector(new DTLSConnector(dtlsConfig))
                .build();
    }

}

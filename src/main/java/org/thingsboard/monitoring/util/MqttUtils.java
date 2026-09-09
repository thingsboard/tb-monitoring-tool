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

import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttUtils {

    public static MqttMessage message(String payload, int qos) {
        MqttMessage message = new MqttMessage();
        message.setPayload(payload.getBytes());
        message.setQos(qos);
        return message;
    }

    // Paho treats connectionTimeout=0 as "wait indefinitely", not "fail fast" - integer
    // division would truncate any sub-second request_timeout_ms to exactly that.
    public static int connectionTimeoutSeconds(int requestTimeoutMs) {
        return Math.max(1, requestTimeoutMs / 1000);
    }

    // Shared by both the transport and integration MQTT health checkers - only how userName/topic
    // are derived differs between the two, not how the client itself is built and connected.
    public static MqttClient connect(String serverUri, String userName, int requestTimeoutMs) throws Exception {
        MqttClient client = new MqttClient(serverUri, MqttAsyncClient.generateClientId(), new MemoryPersistence());
        client.setTimeToWait(requestTimeoutMs);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(userName);
        options.setConnectionTimeout(connectionTimeoutSeconds(requestTimeoutMs));
        IMqttToken result = client.connectWithResult(options);
        if (result.getException() != null) {
            throw result.getException();
        }
        return client;
    }

}

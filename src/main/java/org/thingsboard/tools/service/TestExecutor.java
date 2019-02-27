/**
 * Copyright © 2016-2018 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.device.DeviceAPITest;
import org.thingsboard.tools.service.rule.RuleChainManager;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class TestExecutor {

    @Value("${publish.pause}")
    private int publishTelemetryPause;

    @Autowired
    private DeviceAPITest deviceAPITest;

    @Autowired
    private RuleChainManager ruleChainManager;

    @PostConstruct
    public void init() throws Exception {
        deviceAPITest.createDevices();

        deviceAPITest.warmUpDevices(publishTelemetryPause);

        deviceAPITest.subscribeWebSockets();

        ruleChainManager.createRuleChainWithCountNodeAndSetAsRoot();

        deviceAPITest.runApiTests(publishTelemetryPause);
    }

}

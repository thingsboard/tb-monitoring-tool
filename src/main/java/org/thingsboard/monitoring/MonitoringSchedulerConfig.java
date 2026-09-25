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
package org.thingsboard.monitoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.common.util.ThingsBoardExecutors;

import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class MonitoringSchedulerConfig {

    // shared by ThingsboardMonitoringApplication (initial per-service kickoff) and
    // BaseMonitoringService (per-probe staggering + cycle-to-cycle recurrence) - one thread for
    // all monitoring scheduling, see AGENTS.md
    // destroyMethod = "" - ThingsboardMonitoringApplication.shutdownScheduler() already shuts this
    // down explicitly; without this, Spring's inferred close() also runs and blocks app shutdown
    // waiting for a pending cycle (up to monitoring_rate_ms out) to fire first
    @Bean(destroyMethod = "")
    public ScheduledExecutorService monitoringScheduler() {
        return ThingsBoardExecutors.newSingleThreadScheduledExecutor("monitoring");
    }

}

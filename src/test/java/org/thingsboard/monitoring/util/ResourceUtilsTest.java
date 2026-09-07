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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceUtilsTest {

    @Test
    void substitutesAllPlaceholders() {
        String result = ResourceUtils.substitutePlaceholders(
                "host=${MONITORING:HOST}, port=${MONITORING:PORT}",
                Map.of("HOST", "example.com", "PORT", "1883"),
                "No param found for key %s");

        assertThat(result).isEqualTo("host=example.com, port=1883");
    }

    @Test
    void throwsFormattedMessageWhenKeyIsMissing() {
        assertThatThrownBy(() -> ResourceUtils.substitutePlaceholders(
                "host=${MONITORING:HOST}",
                Map.of(),
                "No param found for key %s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No param found for key HOST");
    }

    @Test
    void invokesCallbackForEachSubstitution() {
        AtomicInteger callCount = new AtomicInteger();
        ResourceUtils.substitutePlaceholders(
                "a=${MONITORING:A}, b=${MONITORING:B}",
                Map.of("A", "1", "B", "2"),
                "No param found for key %s",
                (key, value) -> callCount.incrementAndGet());

        assertThat(callCount.get()).isEqualTo(2);
    }

}

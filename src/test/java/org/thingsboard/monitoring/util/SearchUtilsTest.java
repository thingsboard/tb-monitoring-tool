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
import org.thingsboard.server.common.data.page.PageData;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchUtilsTest {

    @Test
    void findsExactMatchAmongFuzzyResults() {
        PageData<String> page = new PageData<>(List.of("Foo", "Foobar", "Foo Bar"), 3, 1, false);

        Optional<String> result = SearchUtils.findByExactName(() -> page, "Foo", name -> name);

        assertThat(result).contains("Foo");
    }

    @Test
    void returnsEmptyWhenNoExactMatchOnlyFuzzyOnes() {
        PageData<String> page = new PageData<>(List.of("Foobar", "Foo Bar"), 2, 1, false);

        Optional<String> result = SearchUtils.findByExactName(() -> page, "Foo", name -> name);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyPage() {
        Optional<String> result = SearchUtils.findByExactName(PageData::emptyPageData, "Foo", name -> name);

        assertThat(result).isEmpty();
    }

}

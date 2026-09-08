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
import org.thingsboard.server.common.data.page.PageLink;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchUtilsTest {

    @Test
    void findsExactMatchAmongFuzzyResults() {
        PageData<String> page = new PageData<>(List.of("Foo", "Foobar", "Foo Bar"), 1, 3, false);

        Optional<String> result = SearchUtils.findByExactName(pageLink -> page, "Foo", name -> name);

        assertThat(result).contains("Foo");
    }

    @Test
    void returnsEmptyWhenNoExactMatchOnlyFuzzyOnes() {
        PageData<String> page = new PageData<>(List.of("Foobar", "Foo Bar"), 1, 2, false);

        Optional<String> result = SearchUtils.findByExactName(pageLink -> page, "Foo", name -> name);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyPage() {
        Optional<String> result = SearchUtils.findByExactName(pageLink -> PageData.emptyPageData(), "Foo", name -> name);

        assertThat(result).isEmpty();
    }

    @Test
    void paginatesUntilExactMatchFound() {
        // regression test: an exact match must be findable even when it's not on the first page,
        // instead of assuming a generous page size always catches it
        PageData<String> page1 = new PageData<>(List.of("Foobar"), 2, 2, true);
        PageData<String> page2 = new PageData<>(List.of("Foo"), 2, 2, false);
        AtomicInteger calls = new AtomicInteger();

        Optional<String> result = SearchUtils.findByExactName(pageLink -> calls.getAndIncrement() == 0 ? page1 : page2, "Foo", name -> name);

        assertThat(result).contains("Foo");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void queriesWithTheGivenNameAsTheTextSearch() {
        AtomicInteger calls = new AtomicInteger();

        SearchUtils.findByExactName(pageLink -> {
            calls.incrementAndGet();
            assertThat(pageLink.getTextSearch()).isEqualTo("Foo");
            return PageData.<String>emptyPageData();
        }, "Foo", name -> name);

        assertThat(calls.get()).isEqualTo(1);
    }

}

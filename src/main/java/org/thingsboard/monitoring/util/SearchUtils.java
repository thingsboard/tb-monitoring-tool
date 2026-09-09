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

import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;

import java.util.Optional;
import java.util.function.Function;

public class SearchUtils {

    private static final int PAGE_SIZE = 100;

    // The text search behind PageLink can return a partial/fuzzy match, so a name search still needs
    // to be filtered down to an exact match before it can be trusted as "already exists" - paginates
    // until either an exact match turns up or the results run out, rather than assuming a match
    // would always land on the first page.
    public static <T> Optional<T> findByExactName(Function<PageLink, PageData<T>> search, String name, Function<T, String> nameOf) {
        PageLink pageLink = new PageLink(PAGE_SIZE, 0, name);
        PageData<T> page;
        do {
            page = search.apply(pageLink);
            Optional<T> match = page.getData().stream().filter(item -> name.equals(nameOf.apply(item))).findFirst();
            if (match.isPresent()) {
                return match;
            }
            pageLink = pageLink.nextPageLink();
        } while (page.hasNext());
        return Optional.empty();
    }

}

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

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class SearchUtils {

    // Page size to use for the name search backing findByExactName - generous enough that a real
    // exact match won't be pushed past the first page by unrelated fuzzy matches.
    public static final int DEFAULT_PAGE_SIZE = 100;

    // The text search behind PageLink can return a partial/fuzzy match, so a name search still
    // needs to be filtered down to an exact match before it can be trusted as "already exists".
    public static <T> Optional<T> findByExactName(Supplier<PageData<T>> search, String name, Function<T, String> nameOf) {
        return search.get().getData().stream().filter(item -> name.equals(nameOf.apply(item))).findFirst();
    }

}

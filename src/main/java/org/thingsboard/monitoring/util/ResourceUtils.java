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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.RegexUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

public class ResourceUtils {

    @SneakyThrows
    public static <T> T getResource(String path, Class<T> type) {
        InputStream resource = getResourceStream(path);
        return JacksonUtil.OBJECT_MAPPER.readValue(resource, type);
    }

    @SneakyThrows
    public static JsonNode getResource(String path) {
        InputStream resource = getResourceStream(path);
        return JacksonUtil.OBJECT_MAPPER.readTree(resource);
    }

    // For templates with placeholders that must be substituted before the text is valid JSON
    // (e.g. a numeric field), so parsing has to happen after placeholder substitution, not before.
    @SneakyThrows
    public static String getResourceAsString(String path) {
        return new String(getResourceStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    public static InputStream getResourceAsStream(String path) {
        return getResourceStream(path);
    }

    // Substitutes every ${MONITORING:KEY} placeholder in `template` with params.get(KEY), failing
    // loudly on a miss rather than silently leaving the placeholder in place.
    public static String substitutePlaceholders(String template, Map<String, String> params, String missingKeyErrorMessage) {
        return substitutePlaceholders(template, params, missingKeyErrorMessage, null);
    }

    // As above, plus a callback invoked with (key, value) for every placeholder actually substituted.
    public static String substitutePlaceholders(String template, Map<String, String> params, String missingKeyErrorMessage,
                                                  BiConsumer<String, String> onSubstitution) {
        return RegexUtils.replace(template, "\\$\\{MONITORING:(.+?)}", matchResult -> {
            String key = matchResult.group(1);
            String value = params.get(key);
            if (value == null) {
                throw new IllegalArgumentException(String.format(missingKeyErrorMessage, key));
            }
            if (onSubstitution != null) {
                onSubstitution.accept(key, value);
            }
            return value;
        });
    }

    private static InputStream getResourceStream(String path) {
        InputStream resource = ResourceUtils.class.getClassLoader().getResourceAsStream(path);
        if (resource == null) {
            throw new IllegalArgumentException("Resource not found for path " + path);
        }
        return resource;
    }

}

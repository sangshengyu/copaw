/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.copaw.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for getting the current time in various formats and timezones.
 *
 * <p>Provides capabilities:
 * <ul>
 *   <li>Get current time in system default or specified timezone</li>
 *   <li>Support custom time formats</li>
 *   <li>Returns day of week information</li>
 * </ul>
 */
public class GetCurrentTimeTool {

    private static final Logger logger = LoggerFactory.getLogger(GetCurrentTimeTool.class);

    /**
     * Default time format.
     */
    private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * Creates a GetCurrentTimeTool.
     */
    public GetCurrentTimeTool() {
        logger.info("GetCurrentTimeTool initialized");
    }

    /**
     * Get the current time.
     *
     * @param timezone The timezone to use (e.g., "UTC", "America/New_York", "Asia/Shanghai").
     *                 Defaults to system timezone.
     * @param format The time format pattern (e.g., "yyyy-MM-dd HH:mm:ss").
     *               Defaults to "yyyy-MM-dd HH:mm:ss".
     * @return The current time string
     */
    @Tool(
            name = "get_current_time",
            description =
                    "Get the current time in the specified timezone and format. Use this when"
                        + " the current time is needed for any operation.")
    public Mono<ToolResultBlock> getCurrentTime(
            @ToolParam(
                            name = "timezone",
                            description =
                                    "The timezone to use (e.g., UTC, America/New_York,"
                                        + " Asia/Shanghai). Defaults to system timezone.",
                            required = false)
                    String timezone,
            @ToolParam(
                            name = "format",
                            description =
                                    "The time format pattern (e.g., yyyy-MM-dd HH:mm:ss)."
                                        + " Defaults to yyyy-MM-dd HH:mm:ss.",
                            required = false)
                    String format) {

        logger.debug("get_current_time called: timezone='{}', format='{}'", timezone, format);

        return Mono.fromCallable(
                        () -> {
                            // Determine timezone
                            ZoneId zoneId;
                            if (timezone == null || timezone.trim().isEmpty()) {
                                zoneId = ZoneId.systemDefault();
                            } else {
                                try {
                                    zoneId = ZoneId.of(timezone.trim());
                                } catch (Exception e) {
                                    logger.warn("Invalid timezone '{}': {}", timezone, e.getMessage());
                                    return ToolResultBlock.error(
                                            String.format(
                                                    "Invalid timezone '%s'. Please use a valid"
                                                            + " IANA timezone ID (e.g., UTC,"
                                                            + " America/New_York, Asia/Shanghai).",
                                                    timezone));
                                }
                            }

                            // Determine format
                            String formatPattern =
                                    (format != null && !format.trim().isEmpty())
                                            ? format.trim()
                                            : DEFAULT_FORMAT;

                            DateTimeFormatter formatter;
                            try {
                                formatter = DateTimeFormatter.ofPattern(formatPattern, Locale.ENGLISH);
                            } catch (Exception e) {
                                logger.warn("Invalid format pattern '{}': {}", formatPattern, e.getMessage());
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid format pattern '%s'. Please use a valid"
                                                        + " Java DateTimeFormatter pattern.",
                                                formatPattern));
                            }

                            // Get current time
                            ZonedDateTime now = ZonedDateTime.now(zoneId);
                            String formattedTime = now.format(formatter);
                            String dayOfWeek = now.getDayOfWeek().toString();
                            String zoneName = zoneId.getId();

                            String result =
                                    String.format(
                                            "%s %s (%s)",
                                            formattedTime, zoneName, dayOfWeek);

                            logger.debug("Current time: {}", result);

                            return ToolResultBlock.text(result);
                        })
                .onErrorResume(
                        e -> {
                            logger.error("Error getting current time: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "Error getting current time: " + e.getMessage()));
                        });
    }
}

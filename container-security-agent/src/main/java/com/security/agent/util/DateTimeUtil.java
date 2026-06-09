package com.security.agent.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DateTimeUtil {

    private static final DateTimeFormatter REPORT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter TASK_ID_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private DateTimeUtil() {}

    public static String formatTimestamp(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(REPORT_FORMAT);
    }

    public static String nowFormatted() {
        return LocalDateTime.now().format(REPORT_FORMAT);
    }

    public static String taskIdDate() {
        return LocalDateTime.now().format(TASK_ID_DATE_FORMAT);
    }

    public static long nowEpochMillis() {
        return System.currentTimeMillis();
    }
}

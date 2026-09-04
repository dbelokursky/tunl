package com.vlessclient.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a sing-box log line into coloured segments for syntax-highlighted
 * rendering in the Logs view.
 *
 * <p>Recognized parts:</p>
 * <ul>
 *   <li><b>timestamp</b> — optional {@code +0000 2024-01-15 10:30:00} prefix</li>
 *   <li><b>level</b> — {@code TRACE / DEBUG / INFO / WARN / ERROR / FATAL / PANIC}</li>
 *   <li><b>context</b> — optional bracketed tag: {@code [0000]}, {@code [tag]}</li>
 *   <li><b>module</b> — optional {@code module:} prefix of the message body</li>
 *   <li><b>message</b> — everything else</li>
 * </ul>
 *
 * <p>Anything the tokenizer can't classify falls through as a single
 * {@link Kind#PLAIN} segment so callers can render it unchanged.</p>
 */
public final class LogLineFormatter {

    /**
     * Matches an optional timezone offset + date + time prefix, e.g.
     * {@code +0900 2026-04-15 09:22:18}. Each component is independent.
     */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(?:[+\\-]\\d{4}\\s+)?"
                    + "(?:\\d{4}-\\d{2}-\\d{2}\\s+)?"
                    + "(?:\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+)"
    );

    private static final Pattern LEVEL = Pattern.compile(
            "^(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|FATAL|PANIC)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * A bracketed context chunk right after the level, e.g. {@code [0000]}
     * in the sing-box {@code FATAL[0000] ...} format.
     */
    private static final Pattern CONTEXT = Pattern.compile("^\\[[^\\]]*\\]");

    /**
     * A leading {@code module:} prefix of the message body, e.g.
     * {@code network:} in {@code INFO network: updated default interface}.
     */
    private static final Pattern MODULE = Pattern.compile(
            "^([A-Za-z][\\w./\\-]*?):(?=\\s)"
    );

    private LogLineFormatter() {
    }

    /**
     * The level a line carries — one of the {@code LEVEL_*} kinds — or
     * {@link Kind#PLAIN} when the line has none, such as a continuation or a
     * panic trace. Tokenized the same way {@link #format} colours it, so a
     * filter and the rendering can never disagree about a line's level.
     *
     * @param line the raw log line
     * @return the parsed level kind, never null
     */
    public static Kind levelOf(String line) {
        if (line == null || line.isEmpty()) {
            return Kind.PLAIN;
        }
        int pos = 0;
        Matcher ts = TIMESTAMP.matcher(line);
        if (ts.find() && ts.start() == 0) {
            pos = ts.end();
        }
        while (pos < line.length() && line.charAt(pos) == ' ') {
            pos++;
        }
        Matcher lv = LEVEL.matcher(line.substring(pos));
        if (lv.find() && lv.start() == 0) {
            return levelKind(lv.group(1));
        }
        return Kind.PLAIN;
    }

    private static Kind levelKind(String levelText) {
        return switch (levelText.toUpperCase()) {
            case "TRACE", "DEBUG" -> Kind.LEVEL_DEBUG;
            case "INFO" -> Kind.LEVEL_INFO;
            case "WARN", "WARNING" -> Kind.LEVEL_WARN;
            case "ERROR", "FATAL", "PANIC" -> Kind.LEVEL_ERROR;
            default -> Kind.PLAIN;
        };
    }

    /** Returns the styled segments of a single log line. */
    public static List<Segment> format(String line) {
        List<Segment> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }

        int pos = 0;
        int len = line.length();

        // Timestamp
        Matcher ts = TIMESTAMP.matcher(line);
        if (ts.find() && ts.start() == 0) {
            out.add(new Segment(line.substring(0, ts.end()), Kind.TIMESTAMP));
            pos = ts.end();
        }

        // Level (with optional leading space already consumed by timestamp)
        int levelStart = pos;
        while (levelStart < len && line.charAt(levelStart) == ' ') {
            levelStart++;
        }
        if (levelStart > pos) {
            out.add(new Segment(line.substring(pos, levelStart), Kind.PLAIN));
            pos = levelStart;
        }

        Kind lineKind = Kind.PLAIN;
        Matcher lv = LEVEL.matcher(line.substring(pos));
        if (lv.find() && lv.start() == 0) {
            Kind kind = levelKind(lv.group(1));
            lineKind = kind;
            out.add(new Segment(line.substring(pos, pos + lv.end()), kind));
            pos += lv.end();
        }

        // Context right after level: [0000]
        if (pos < len) {
            Matcher ctx = CONTEXT.matcher(line.substring(pos));
            if (ctx.find() && ctx.start() == 0) {
                out.add(new Segment(line.substring(pos, pos + ctx.end()), Kind.CONTEXT));
                pos += ctx.end();
            }
        }

        // Whitespace after level/context
        int afterWs = pos;
        while (afterWs < len && Character.isWhitespace(line.charAt(afterWs))) {
            afterWs++;
        }
        if (afterWs > pos) {
            out.add(new Segment(line.substring(pos, afterWs), Kind.PLAIN));
            pos = afterWs;
        }

        // Module prefix: network:
        if (pos < len) {
            Matcher mod = MODULE.matcher(line.substring(pos));
            if (mod.find() && mod.start() == 0) {
                out.add(new Segment(line.substring(pos, pos + mod.end()), Kind.MODULE));
                pos += mod.end();
            }
        }

        // Everything else is the message. Tint it with the level colour so
        // error lines visually stand out even past the level token.
        if (pos < len) {
            Kind msgKind = switch (lineKind) {
                case LEVEL_ERROR -> Kind.MSG_ERROR;
                case LEVEL_WARN -> Kind.MSG_WARN;
                default -> Kind.MSG;
            };
            out.add(new Segment(line.substring(pos), msgKind));
        }

        return out;
    }

    /** Kinds of styled segments. Each maps to a CSS style class in Logs view. */
    public enum Kind {
        TIMESTAMP("log-ts"),
        LEVEL_DEBUG("log-level-debug"),
        LEVEL_INFO("log-level-info"),
        LEVEL_WARN("log-level-warn"),
        LEVEL_ERROR("log-level-error"),
        CONTEXT("log-context"),
        MODULE("log-module"),
        MSG("log-msg"),
        MSG_WARN("log-msg-warn"),
        MSG_ERROR("log-msg-error"),
        PLAIN("log-plain");

        private final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    /** A contiguous run of text with one style kind. */
    public record Segment(String text, Kind kind) {
    }
}

package me.drex.polymerpatcher.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.regex.Pattern;

/**
 * Keeps other mods' known noise out of the server log, so what is left is worth reading.
 * <p>
 * Three sources were filling it: the Async mod reporting (with a full stack trace) every mob tick it had
 * to skip because two of its threads touched the same mob, a diagnostics mod printing a dozen timing lines
 * every few seconds, and the leftover {@code frozenlib:wind} data in chunks saved while FrozenLib was
 * installed. None of it is actionable, and together they buried everything else. Only those exact
 * messages are dropped; everything else reaches the log as before.
 */
public final class LogQuieter extends AbstractFilter {

    private static final Pattern NOISE = Pattern.compile(
        "^(Error during async tick\\. Entity: .*"
            + "|\\[(mspt|light|block-tick|fluid-tick|worldtick|entity-tick|movement-internals|monster-phase"
            + "|target-scan|entity-query|entity-query-cache|collider-skip|goal-selector|move-control|look-control"
            + "|nav-tick|nav-hostile|nav-passive|prechunk|server-tick-phase|physics-dispatch|cramming-dispatch)\\] .*"
            + "|Skipping invalid attachments: Found unknown attachment type frozenlib:.*)$",
        Pattern.DOTALL);

    private LogQuieter() {
        super(Result.DENY, Result.NEUTRAL);
    }

    public static void install() {
        try {
            if (LogManager.getRootLogger() instanceof Logger root) {
                root.getContext().getConfiguration().getRootLogger().addFilter(new LogQuieter());
                root.getContext().updateLoggers();
            }
        } catch (Throwable ignored) {
            // A log that stays loud is no reason to stop anything else
        }
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        if (event.getLevel().isMoreSpecificThan(Level.FATAL)) {
            return Result.NEUTRAL;
        }
        String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        return message != null && NOISE.matcher(message).matches() ? Result.DENY : Result.NEUTRAL;
    }
}

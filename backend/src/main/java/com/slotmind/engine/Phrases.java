package com.slotmind.engine;

import com.slotmind.domain.Specialty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Patient-friendly wording helpers shared by strategies. */
public final class Phrases {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    private Phrases() {}

    /** "today at 3:30 PM", "tomorrow at 9:00 AM", "on Thursday, October 22 at 2:00 PM". */
    public static String when(LocalDateTime time, LocalDateTime now) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate());
        String t = time.format(TIME);
        if (days == 0) return "today at " + t;
        if (days == 1) return "tomorrow at " + t;
        return "on " + time.format(DAY) + " at " + t;
    }

    public static String specialty(Specialty s) {
        return switch (s) {
            case GENERAL -> "a general practitioner";
            case SPECIALIST -> "a specialist";
            case PEDIATRIC -> "a pediatrician";
        };
    }
}

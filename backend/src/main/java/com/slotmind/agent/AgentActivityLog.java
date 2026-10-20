package com.slotmind.agent;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** In-memory feed of what the agent did, so the front desk can see the loop working. */
@Component
public class AgentActivityLog {

    public enum Level { INFO, SUCCESS, WARN }

    public record Entry(LocalDateTime at, Level level, String message) {}

    private static final int MAX = 100;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(Level level, String message) {
        entries.addFirst(new Entry(LocalDateTime.now(), level, message));
        while (entries.size() > MAX) entries.removeLast();
    }

    public void info(String message) { add(Level.INFO, message); }

    public void success(String message) { add(Level.SUCCESS, message); }

    public void warn(String message) { add(Level.WARN, message); }

    public synchronized List<Entry> recent() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}

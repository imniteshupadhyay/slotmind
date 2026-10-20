package com.slotmind.engine;

import java.util.List;
import java.util.function.Consumer;

/**
 * The scheduling contract. A new strategy (e.g. RoomConstraintStrategy in sprint 2) only has to
 * implement this and be a Spring bean; the {@link SchedulingEngine} registers it by {@link #name()}.
 */
public interface SchedulingStrategy {

    /** Registry key, used in config ({@code slotmind.strategy.active}) and PUT /config/strategy. */
    String name();

    /** Ordered recommendations, best first. Empty when nothing suitable exists. May throw on failure. */
    List<Recommendation> recommend(SchedulingContext context);

    /** Same as {@link #recommend(SchedulingContext)}, reporting progress text as it is produced. */
    default List<Recommendation> recommend(SchedulingContext context, Consumer<String> progress) {
        return recommend(context);
    }
}

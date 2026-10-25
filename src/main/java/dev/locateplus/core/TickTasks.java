/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.core;

import java.util.ArrayList;
import java.util.List;

/** Delayed and repeating work on the server thread. */
public final class TickTasks {

    private static final List<BehaviorControl> TASKS = new ArrayList<>();
    private static final Object LOCK = new Object();

    private TickTasks() {
    }

    /**
     * What a task belongs to, so one kind can be cancelled without disturbing the others.
     *
     * Needed because these queues outlive the command that created them: cancelling every pending
     * task to stop one effect would also drop the timers that switch off glow, leaving entities lit
     * permanently.
     */
    public enum Kind {

        /** One-shot timers that clear the glow flag on an entity. */
        GLOW,
        /** One-shot timers that take back a glowing block marker. */
        BEACON,
        /** Anything not worth distinguishing. */
        OTHER
    }

    /** Run {@code action} once, {@code delayTicks} from now. */
    public static void schedule(int delayTicks, Runnable action) {
        schedule(Kind.OTHER, delayTicks, action);
    }

    /** Run {@code action} once, {@code delayTicks} from now, tagged for selective cancelling. */
    public static void schedule(Kind kind, int delayTicks, Runnable action) {
        synchronized (LOCK) {
            TASKS.add(new BehaviorControl(kind, action, Math.max(0, delayTicks), 0, 1));
        }
    }

    /**
     * Run {@code action} every {@code intervalTicks} until it has run {@code repeats} times. The
     * first run happens one interval from now.
     */
    public static void scheduleRepeating(int intervalTicks, int repeats, Runnable action) {
        scheduleRepeating(Kind.OTHER, intervalTicks, repeats, action);
    }

    /** As {@link #scheduleRepeating(int, int, Runnable)}, tagged for selective cancelling. */
    public static void scheduleRepeating(Kind kind, int intervalTicks, int repeats,
                                         Runnable action) {
        int interval = Math.max(1, intervalTicks);
        synchronized (LOCK) {
            TASKS.add(new BehaviorControl(kind, action, interval, interval, Math.max(1, repeats)));
        }
    }

    /**
     * Drop every pending task of one kind.
     *
     * @return how many were cancelled
     */
    public static int clear(Kind kind) {
        synchronized (LOCK) {
            int before = TASKS.size();
            TASKS.removeIf(task -> task.kind == kind);
            return before - TASKS.size();
        }
    }

    /** Must be called from the server thread, once per tick. */
    public static void tick() {
        List<BehaviorControl> snapshot;
        synchronized (LOCK) {
            if (TASKS.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(TASKS);
        }

        List<BehaviorControl> finished = null;
        for (BehaviorControl task : snapshot) {
            if (--task.ticksRemaining > 0) {
                continue;
            }
            try {
                task.action.run();
            } catch (Throwable t) {
                LPLog.error("Scheduled task failed", t);
                task.runsRemaining = 0;
            }
            if (--task.runsRemaining <= 0) {
                if (finished == null) {
                    finished = new ArrayList<>(4);
                }
                finished.add(task);
            } else {
                task.ticksRemaining = task.interval;
            }
        }

        if (finished != null) {
            synchronized (LOCK) {
                TASKS.removeAll(finished);
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            TASKS.clear();
        }
    }

    private static final class BehaviorControl {
        final Kind kind;
        final Runnable action;
        final int interval;
        int ticksRemaining;
        int runsRemaining;

        BehaviorControl(Kind kind, Runnable action, int delay, int interval, int runs) {
            this.kind = kind;
            this.action = action;
            this.ticksRemaining = Math.max(1, delay);
            this.interval = Math.max(1, interval);
            this.runsRemaining = runs;
        }
    }
}

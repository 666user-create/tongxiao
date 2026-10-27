package com.course.tongxiao.group;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One-time wiring of the alarm-side {@link AlarmGateway} for the group feature.
 *
 * <p>The group package must not construct the gateway itself: the implementation
 * lives in the alarm layer and needs the repository, scheduler and ring actions.
 * The app installs it once at startup, and {@code GroupFragment} picks it up when
 * the dorm page opens.
 *
 * <p>Deliberately a single static field rather than DI: this project uses manual
 * injection (no Hilt, see the plan's global conventions), and there is exactly one
 * gateway per process.
 */
public final class GroupWiring {

    @Nullable
    private static volatile AlarmGateway gateway;

    private GroupWiring() {
    }

    /**
     * Called once from the {@code Application} (or before the dorm page is opened):
     * {@code GroupWiring.install(new AlarmGatewayImpl(db, repository, scheduler, ringActions, context))}.
     */
    public static void install(@NonNull AlarmGateway implementation) {
        gateway = implementation;
    }

    /**
     * @return the installed gateway, or null when the app has not wired one yet.
     *         The dorm page keeps working without it (it shows a warning and remote
     *         commands are answered with INTERNAL_ERROR instead of being applied).
     */
    @Nullable
    public static AlarmGateway gatewayOrNull() {
        return gateway;
    }
}

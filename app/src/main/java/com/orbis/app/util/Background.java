package com.orbis.app.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one background pool for disk and system-service work.
 *
 * <p>This is what the Kotlin build used {@code Dispatchers.IO} for. Two threads
 * rather than one: a usage refresh can take a second on a busy phone, and the
 * earn ledger should not queue behind it.
 *
 * <p>Daemon threads, so they never hold the process open on their own - the
 * accessibility service and the tunnel decide the process's lifetime, not this.
 */
public final class Background {

    private static final ExecutorService IO =
            Executors.newFixedThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "orbis-io");
                        thread.setDaemon(true);
                        return thread;
                    });

    private Background() {
        // No instances: one process-wide pool.
    }

    public static void run(Runnable task) {
        IO.execute(task);
    }

    public static ExecutorService io() {
        return IO;
    }
}

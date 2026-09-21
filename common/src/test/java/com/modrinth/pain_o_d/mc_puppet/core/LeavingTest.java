package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("seeing a stopped server's process out")
class LeavingTest {

    @Test
    @DisplayName("the watcher is a daemon: a process that ends by itself is not kept by it")
    void theWatcherIsADaemon() {
        Thread watcher = Leaving.after(60_000, () -> { });
        try {
            assertTrue(watcher.isDaemon());
        } finally {
            watcher.interrupt();
        }
    }

    @Test
    @DisplayName("it acts once the grace has passed, and not before")
    void actsAfterTheGrace() throws InterruptedException {
        CountDownLatch acted = new CountDownLatch(1);
        Leaving.after(200, acted::countDown);

        assertFalse(acted.await(50, TimeUnit.MILLISECONDS), "before the grace was over");
        assertTrue(acted.await(5, TimeUnit.SECONDS), "after it");
    }

    @Test
    @DisplayName("interrupted, it does nothing")
    void interruptedDoesNothing() throws InterruptedException {
        CountDownLatch acted = new CountDownLatch(1);
        Thread watcher = Leaving.after(300, acted::countDown);
        watcher.interrupt();
        watcher.join(5_000);

        assertFalse(acted.await(600, TimeUnit.MILLISECONDS));
    }
}

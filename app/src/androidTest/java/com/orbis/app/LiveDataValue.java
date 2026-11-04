package com.orbis.app;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads one value out of a {@link LiveData} from a test thread.
 *
 * <p>This is what the Kotlin tests got from {@code flow.first()}. LiveData only
 * delivers on the main thread, so the observer is attached there and the test
 * thread waits for the first emission.
 */
public final class LiveDataValue {

    private LiveDataValue() {}

    public static <T> T of(LiveData<T> source) throws InterruptedException {
        AtomicReference<T> holder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Observer<T> observer =
                new Observer<T>() {
                    @Override
                    public void onChanged(T value) {
                        holder.set(value);
                        latch.countDown();
                        source.removeObserver(this);
                    }
                };

        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> source.observeForever(observer));

        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("LiveData never emitted");
        }
        return holder.get();
    }
}

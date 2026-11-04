package com.orbis.app.util;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * One value, readable from any thread and observable from the UI.
 *
 * <p>This is what the Kotlin build used {@code StateFlow} for, and it exists for
 * the same reason: the accessibility service decides things the UI has to
 * display, and an {@link android.accessibilityservice.AccessibilityService} is
 * owned by the system and cannot be bound to conventionally, so the two meet at
 * a process-wide holder instead.
 *
 * <p>Two access paths on purpose. {@link #get()} reads a volatile field and is
 * what the service's hot path uses - it runs several times a second and must not
 * touch the main thread. {@link #asLiveData()} is what screens observe, and is
 * updated with {@code postValue} so publishing never has to happen on the main
 * thread either.
 */
public final class StateHolder<T> {

    private volatile T value;
    private final MutableLiveData<T> live;

    public StateHolder(@NonNull T initial) {
        this.value = initial;
        this.live = new MutableLiveData<>(initial);
    }

    /** The current value. Safe from any thread. */
    @NonNull
    public T get() {
        return value;
    }

    /** Publishes a new value. Safe from any thread. */
    public void set(@NonNull T next) {
        value = next;
        live.postValue(next);
    }

    /** For screens to observe. Delivered on the main thread. */
    @NonNull
    public LiveData<T> asLiveData() {
        return live;
    }
}

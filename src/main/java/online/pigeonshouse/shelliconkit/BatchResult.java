package online.pigeonshouse.shelliconkit;

import java.io.IOException;
import java.util.Optional;

/** The value or I/O error for one input in a batch. */
public final class BatchResult<T> {
    private final T value;
    private final IOException error;

    private BatchResult(T value, IOException error) {
        this.value = value;
        this.error = error;
    }

    static <T> BatchResult<T> success(T value) {
        return new BatchResult<>(value, null);
    }

    static <T> BatchResult<T> failure(IOException error) {
        return new BatchResult<>(null, error);
    }

    public boolean isSuccess() {
        return error == null;
    }

    public Optional<IOException> getError() {
        return Optional.ofNullable(error);
    }

    /** Returns the value or throws this item's error. */
    public T get() throws IOException {
        if (error != null) {
            throw error;
        }
        return value;
    }
}

package io.github.bitlhk.finance.harness.core.observation;

@FunctionalInterface
public interface ObservationSink {
    /** Return false if not accepted. Implementations must bound storage and must not log bodies. */
    boolean append(ObservationEventV01 event);
}

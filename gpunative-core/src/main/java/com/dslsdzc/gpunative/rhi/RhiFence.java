package com.dslsdzc.gpunative.rhi;

public interface RhiFence {
    enum Status {
        READY,
        NOT_READY,
        ERROR
    }

    Status waitFor(long timeoutNanos);
    Status getStatus();
    void reset();
    long getHandle();
    void destroy();
}

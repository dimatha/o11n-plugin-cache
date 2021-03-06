package com.vmware.o11n.plugin.cache.model;

public enum TimeUnit {
    SECONDS, MINUTES, HOURS, DAYS;

    public java.util.concurrent.TimeUnit convertToConcurrentTimeUnit() {
        return java.util.concurrent.TimeUnit.valueOf(name());
    }
}

package com.portfolio;

public record ServiceSpec(String name, String pathPattern, int priority,
                          int cpu, int memoryMiB, int desiredCount, String healthPath) {
    /** Services with a path pattern sit behind the ALB; others (workers) are private only. */
    public boolean exposed() { return pathPattern != null; }
}

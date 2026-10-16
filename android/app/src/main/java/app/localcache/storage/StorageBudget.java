package app.localcache.storage;

/** Logical quota reservations and actual remaining disk allocation are separate budgets. */
public final class StorageBudget {
    private StorageBudget() {}

    public static long remaining(long total, long existing) {
        return Math.max(0, total - Math.max(0, existing));
    }

    public static long room(long quota, long usage, long free, long headroom) {
        return Math.max(0, Math.min(quota - usage, free - headroom));
    }

    public static boolean fits(long quota, long usage, long quotaGrowth,
                               long free, long diskGrowth, long headroom) {
        return quotaGrowth <= Math.max(0, quota - usage) && usage <= quota
            && diskGrowth <= Math.max(0, free - headroom) && free >= headroom;
    }
}

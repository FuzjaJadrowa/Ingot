package pl.fuzjajadrowa.ingot.storage;

record RegionStatistics(
        long totalFileBytes,
        long committedRecords,
        int liveChunks,
        long liveRecordBytes,
        long reclaimableBytes,
        double obsoleteRatio,
        long generation,
        boolean compactionRecommended
) {
    static final long MINIMUM_FILE_BYTES = 2L * 1024 * 1024;
    static final long MINIMUM_RECLAIMABLE_BYTES = 1024L * 1024;
    static final double MINIMUM_OBSOLETE_RATIO = 0.30;
}
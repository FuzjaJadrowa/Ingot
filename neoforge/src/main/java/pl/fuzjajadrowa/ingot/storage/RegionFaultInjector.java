package pl.fuzjajadrowa.ingot.storage;

import java.io.IOException;

@FunctionalInterface
interface RegionFaultInjector {
    enum Point {
        AFTER_HEADER, AFTER_PAYLOAD, AFTER_DATA_FORCE, AFTER_COMMIT, AFTER_COMMIT_FORCE,
        DURING_CANDIDATE, AFTER_CANDIDATE, AFTER_VALIDATION, AFTER_SOURCE_CLOSE, AFTER_PUBLISH
    }

    RegionFaultInjector NONE = point -> { };

    void checkpoint(Point point) throws IOException;
}
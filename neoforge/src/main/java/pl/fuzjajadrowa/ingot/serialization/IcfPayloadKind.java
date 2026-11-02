package pl.fuzjajadrowa.ingot.serialization;

import java.io.IOException;

public enum IcfPayloadKind {
    CHUNK(1),
    ENTITIES(2),
    POI(3);

    private final int id;

    IcfPayloadKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static IcfPayloadKind fromId(int id) throws IOException {
        for (IcfPayloadKind kind : values()) {
            if (kind.id == id) return kind;
        }
        throw new IOException("Unsupported ICF payload kind " + id);
    }
}
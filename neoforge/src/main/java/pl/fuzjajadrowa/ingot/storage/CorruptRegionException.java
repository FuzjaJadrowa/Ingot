package pl.fuzjajadrowa.ingot.storage;

import java.io.IOException;

public final class CorruptRegionException extends IOException {
    public CorruptRegionException(String message) { super(message); }
    public CorruptRegionException(String message, Throwable cause) { super(message, cause); }
}
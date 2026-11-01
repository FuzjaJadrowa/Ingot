package pl.fuzjajadrowa.ingot.conversion;

import java.io.IOException;

public final class ConversionException extends IOException {
    public ConversionException(String message) { super(message); }
    public ConversionException(String message, Throwable cause) { super(message, cause); }
}
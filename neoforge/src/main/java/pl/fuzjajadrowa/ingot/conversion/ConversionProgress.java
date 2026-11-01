package pl.fuzjajadrowa.ingot.conversion;

public record ConversionProgress(Stage stage, String operation, long completed, long total) {
    public enum Stage { PREFLIGHT, STAGING, VERIFICATION, PUBLICATION, COMPLETE }

    public ConversionProgress {
        if (completed < 0 || total < 0 || (total > 0 && completed > total)) {
            throw new IllegalArgumentException("Invalid conversion progress");
        }
    }

    public double fraction() {
        return total == 0 ? 0.0 : (double) completed / total;
    }
}
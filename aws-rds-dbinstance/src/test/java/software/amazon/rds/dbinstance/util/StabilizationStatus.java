package software.amazon.rds.dbinstance.util;

public enum StabilizationStatus {
    Stabilized(true),
    NotStabilized(false);

    final boolean isStabilized;
    StabilizationStatus(boolean isStabilized){
        this.isStabilized = isStabilized;
    }

    public boolean isStabilized() {
        return isStabilized;
    }
}


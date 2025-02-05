package software.amazon.rds.dbinstance.util;

public enum ApplyImmediately {
    Enabled(true),
    Disabled(false);

    final boolean status;
    ApplyImmediately(boolean status) {
        this.status = status;
    }

    public  boolean isEnabled(){
        return status;
    }
}

package ir.chobyar.sketch;

/**
 * Opaque V2 body reference. UI and command code should never depend on the
 * native numeric handle. Only the kernel adapter may unwrap it.
 */
public final class CadBodyRef {
    private final long id;

    CadBodyRef(long id) {
        if (id <= 0L) {
            throw new IllegalArgumentException("CAD body id must be positive");
        }
        this.id = id;
    }

    static CadBodyRef fromNative(long nativeHandle) {
        return new CadBodyRef(nativeHandle);
    }

    long nativeHandle() {
        return id;
    }

    public boolean isValid() {
        return id > 0L;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CadBodyRef && id == ((CadBodyRef) other).id);
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
    }

    @Override
    public String toString() {
        return "CadBodyRef{opaque}";
    }
}

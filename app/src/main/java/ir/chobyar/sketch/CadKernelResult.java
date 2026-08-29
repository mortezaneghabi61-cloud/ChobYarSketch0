package ir.chobyar.sketch;

/**
 * Typed result returned by the V2 CAD-kernel boundary.
 *
 * V1 commonly encoded native failures as 0, false, or empty arrays. V2 keeps
 * failure meaning explicit so command/UI layers can make correct decisions.
 */
public final class CadKernelResult<T> {
    public enum Code {
        OK,
        UNAVAILABLE,
        INVALID_ARGUMENT,
        NOT_FOUND,
        UNSUPPORTED,
        NATIVE_FAILURE
    }

    private final Code code;
    private final T value;
    private final String message;

    private CadKernelResult(Code code, T value, String message) {
        this.code = code == null ? Code.NATIVE_FAILURE : code;
        this.value = value;
        this.message = message == null ? "" : message;
    }

    public static <T> CadKernelResult<T> success(T value) {
        return new CadKernelResult<>(Code.OK, value, "");
    }

    public static <T> CadKernelResult<T> failure(Code code, String message) {
        if (code == null || code == Code.OK) {
            throw new IllegalArgumentException("Failure result requires a non-OK code");
        }
        return new CadKernelResult<>(code, null, message);
    }

    public boolean isSuccess() {
        return code == Code.OK;
    }

    public Code code() {
        return code;
    }

    public T value() {
        return value;
    }

    public String message() {
        return message;
    }

    public T requireValue() {
        if (!isSuccess()) {
            throw new IllegalStateException("CAD kernel result failed: " + code + " " + message);
        }
        return value;
    }

    @Override
    public String toString() {
        return isSuccess() ? "CadKernelResult{OK}" : "CadKernelResult{" + code + ": " + message + "}";
    }
}

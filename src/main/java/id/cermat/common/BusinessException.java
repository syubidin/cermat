package id.cermat.common;

/** Pelanggaran aturan bisnis (saldo kurang, rekening dibekukan, dll). Dipetakan ke HTTP 422. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}

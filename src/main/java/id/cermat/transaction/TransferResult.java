package id.cermat.transaction;

/** replayed = true berarti ini jawaban ulang untuk request duplikat, tidak ada uang yang bergerak lagi. */
public record TransferResult(LedgerTransaction transaction, boolean replayed) {}

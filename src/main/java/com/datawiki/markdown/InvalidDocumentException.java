package com.datawiki.markdown;

/** The input cannot be accepted as a document; {@link #reason()} goes into the import report. */
public class InvalidDocumentException extends RuntimeException {

    public enum Reason { TOO_LARGE, NOT_TEXT, EMPTY }

    private final Reason reason;

    public InvalidDocumentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

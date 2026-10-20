package com.slotmind.domain;

/** Thrown when an operation would violate a domain rule (illegal transition, double booking, ...). */
public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
}

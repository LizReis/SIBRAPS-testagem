package com.pet.buscaativa.services.exceptions;

/** Invalid business input reported to API clients with HTTP 422. */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
package com.pet.buscaativa.services.exceptions;

/**
 * Exceção para erros de validação / violação de regras de negócio.
 * Será mapeada para HTTP 400 (Bad Request) pelo controller ou pelo GlobalExceptionHandler.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
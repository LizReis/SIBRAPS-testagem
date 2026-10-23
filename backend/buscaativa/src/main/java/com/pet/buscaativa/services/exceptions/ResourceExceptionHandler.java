package com.pet.buscaativa.services.exceptions;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice
public class ResourceExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<StandardError> resourceNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        String error = "Recurso não encontrado.";
        HttpStatus status = HttpStatus.NOT_FOUND;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(DatabaseException.class)
    public ResponseEntity<StandardError> database(DatabaseException e, HttpServletRequest request) {
        String error = "Erro de regra de negócio.";
        HttpStatus status = HttpStatus.BAD_REQUEST;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<StandardError> validation(ValidationException e, HttpServletRequest request) {
        String error = "Erro de validação.";
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(RecursoDuplicadoException.class)
    public ResponseEntity<StandardError> recursoDuplicado(RecursoDuplicadoException e, HttpServletRequest request) {
        String error = "Recurso duplicado.";
        HttpStatus status = HttpStatus.CONFLICT;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<StandardError> conflict(ConflictException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        StandardError error = new StandardError(Instant.now(), status.value(), "Conflito de regra de negócio.",
                e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }


    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<StandardError> accessDenied(AccessDeniedException e, HttpServletRequest request) {
        String error = "Acesso negado.";
        HttpStatus status = HttpStatus.FORBIDDEN;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error,
                "Você não tem permissão para realizar esta ação.", request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationError> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String error = "Dados inválidos.";
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        ValidationError verr = new ValidationError(Instant.now(), status.value(), error,
                "Um ou mais campos estão inválidos. Verifique e tente novamente.", request.getRequestURI());

        for (FieldError f : e.getBindingResult().getFieldErrors()) {
            verr.addError(f.getField(), f.getDefaultMessage());
        }

        return ResponseEntity.status(status).body(verr);
    }

    @ExceptionHandler(jakarta.persistence.OptimisticLockException.class)
    public ResponseEntity<StandardError> optimisticLock(jakarta.persistence.OptimisticLockException e, HttpServletRequest request) {
        String error = "Conflito de atualização.";
        HttpStatus status = HttpStatus.CONFLICT;
        StandardError sterr = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        return ResponseEntity.status(status).body(sterr);
    }

    @ExceptionHandler(RelatorioException.class)
    public ResponseEntity<StandardError> relatorio(RelatorioException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        StandardError error = new StandardError(Instant.now(), status.value(), "Erro ao gerar relatório.",
                "Não foi possível gerar o PDF solicitado.", request.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }
}
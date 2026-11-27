package com.pet.buscaativa.services.exceptions;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice
public class ResourceExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger diagnostics = LoggerFactory.getLogger(ResourceExceptionHandler.class);
    private static final String INVALID_FIELDS = "Um ou mais campos estão inválidos. Verifique e tente novamente.";
    private static final String INTERNAL_MESSAGE = "Não foi possível concluir a operação. Informe o identificador do erro ao suporte.";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardError> applicationFailure(Exception failure, HttpServletRequest request) {
        StandardError body = domainError(failure, request);
        if (body != null) return response(body);

        List<Throwable> causes = PersistenceFailure.causes(failure);
        var persistence = PersistenceFailure.classify(causes);
        if (persistence == null || persistence == PersistenceFailure.UNCLASSIFIED) {
            for (Throwable cause : causes.subList(1, causes.size())) {
                body = domainError(cause, request);
                if (body != null) return response(body);
            }
        }

        if (persistence != null) {
            body = persistenceError(request, persistence);
        } else if (causes.stream().anyMatch(RelatorioException.class::isInstance)) {
            body = ApiErrorResponses.body(request, 500, "REPORT_GENERATION_FAILED", "Erro ao gerar relatório.",
                    "Não foi possível gerar o relatório solicitado.");
        } else {
            body = internalError(request);
        }
        logFailure(request, body, causes);
        return response(body);
    }

    private StandardError domainError(Throwable failure, HttpServletRequest request) {
        return switch (failure) {
            case BadCredentialsException ignored -> ApiErrorResponses.authenticationRequired(request);
            case UsernameNotFoundException ignored -> ApiErrorResponses.authenticationRequired(request);
            case AccountStatusException ignored -> ApiErrorResponses.authenticationRequired(request);
            case AccessDeniedException ignored -> ApiErrorResponses.accessDenied(request);
            case ResourceNotFoundException ignored -> notFound(request);
            case SimilarPatientException e -> ApiErrorResponses.body(request, 400, "PATIENT_SIMILARITY_CONFIRMATION",
                    "Erro de regra de negócio.", e.getMessage());
            case StaleLocalVersionException e -> ApiErrorResponses.body(request, 422, "STALE_VERSION",
                    "Erro de validação.", e.getMessage());
            case ValidationException e -> ApiErrorResponses.body(request, 422, "VALIDATION_FAILED",
                    "Erro de validação.", e.getMessage());
            case RecursoDuplicadoException e -> ApiErrorResponses.body(request, 409, "DUPLICATE_RESOURCE",
                    "Recurso duplicado.", e.getMessage());
            case ConflictException e -> ApiErrorResponses.body(request, 409, "BUSINESS_CONFLICT",
                    "Conflito de regra de negócio.", e.getMessage());
            default -> null;
        };
    }

    private StandardError persistenceError(HttpServletRequest request, PersistenceFailure failure) {
        return switch (failure) {
            case VERSION -> ApiErrorResponses.body(request, 409, "STALE_VERSION", "Conflito de atualização.",
                    "Os dados estão desatualizados. Atualize a consulta antes de tentar novamente.");
            case DEADLOCK, LOCK_TIMEOUT, CONCURRENCY -> ApiErrorResponses.body(request, 409, switch (failure) {
                case DEADLOCK -> "CONCURRENT_DEADLOCK";
                case LOCK_TIMEOUT -> "LOCK_ACQUISITION_TIMEOUT";
                default -> "CONCURRENT_UPDATE";
            }, "Conflito de atualização.",
                    "Não foi possível concluir a operação concorrente. Atualize os dados antes de tentar novamente.");
            case UNAVAILABLE -> ApiErrorResponses.body(request, 503, "SERVICE_UNAVAILABLE", "Serviço temporariamente indisponível.",
                    "Não foi possível concluir a operação neste momento. Verifique o estado dos dados antes de tentar novamente.");
            case CONNECTION_TIMEOUT -> databaseError(request, "DATABASE_CONNECTION_TIMEOUT");
            case CONNECTION -> databaseError(request, "DATABASE_CONNECTION_FAILED");
            case QUERY_TIMEOUT -> databaseError(request, "DATABASE_QUERY_TIMEOUT");
            case INTEGRITY -> databaseError(request, "DATABASE_INTEGRITY_ERROR");
            case DATABASE_OPERATION, UNCLASSIFIED -> databaseError(request, "DATABASE_ERROR");
        };
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object ignoredBody, HttpHeaders headers,
            HttpStatusCode status, WebRequest webRequest) {
        var request = ((ServletWebRequest) webRequest).getRequest();
        StandardError body;
        if (failure instanceof MethodArgumentNotValidException validation) {
            var fields = validationError(request);
            for (FieldError field : validation.getBindingResult().getFieldErrors()) {
                fields.addError(field.getField(), field.isBindingFailure() ? "Valor inválido." : field.getDefaultMessage());
            }
            body = fields;
        } else if (failure instanceof HandlerMethodValidationException validation && !validation.isForReturnValue()) {
            var fields = validationError(request);
            for (var parameter : validation.getParameterValidationResults()) {
                String name = parameter.getMethodParameter().getParameterName();
                for (var error : parameter.getResolvableErrors()) {
                    fields.addError(error instanceof FieldError field ? field.getField() : name, "Valor inválido.");
                }
            }
            body = fields;
        } else {
            body = frameworkError(request, failure, status);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        responseHeaders.set(ApiErrorResponses.CORRELATION_HEADER, body.getCorrelationId());
        return super.handleExceptionInternal(failure, body, responseHeaders, HttpStatusCode.valueOf(body.getStatus()), webRequest);
    }

    private StandardError frameworkError(HttpServletRequest request, Exception failure, HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ApiErrorResponses.body(request, 400, "MALFORMED_REQUEST", "Requisição inválida.",
                    "Confira o formato do corpo da requisição e dos parâmetros.");
            case 401 -> ApiErrorResponses.authenticationRequired(request);
            case 403 -> ApiErrorResponses.accessDenied(request);
            case 404 -> notFound(request);
            default -> {
                var body = status.value() == 500 ? internalError(request) : status.is5xxServerError()
                        ? ApiErrorResponses.body(request, status.value(), "HTTP_" + status.value(), "Falha no serviço.", INTERNAL_MESSAGE)
                        : ApiErrorResponses.body(request, status.value(), "HTTP_" + status.value(), "Requisição recusada.",
                                "Confira os parâmetros e os formatos aceitos pelo recurso.");
                if (status.is5xxServerError()) logFailure(request, body, PersistenceFailure.causes(failure));
                yield body;
            }
        };
    }

    private static ValidationError validationError(HttpServletRequest request) {
        var body = new ValidationError(java.time.Instant.now(), 422, "Dados inválidos.", INVALID_FIELDS, request.getRequestURI());
        body.setCode("VALIDATION_FAILED");
        body.setCorrelationId(ApiErrorResponses.correlationId(request));
        return body;
    }

    private static StandardError notFound(HttpServletRequest request) {
        return ApiErrorResponses.body(request, 404, "RESOURCE_NOT_FOUND", "Recurso não encontrado.",
                "O recurso solicitado não foi encontrado.");
    }

    private static StandardError databaseError(HttpServletRequest request, String code) {
        return ApiErrorResponses.body(request, 500, code, "Erro de banco de dados.", INTERNAL_MESSAGE);
    }

    private static StandardError internalError(HttpServletRequest request) {
        return ApiErrorResponses.body(request, 500, "INTERNAL_ERROR", "Erro interno.", INTERNAL_MESSAGE);
    }

    private static ResponseEntity<StandardError> response(StandardError body) {
        return ResponseEntity.status(body.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .header(ApiErrorResponses.CORRELATION_HEADER, body.getCorrelationId()).body(body);
    }

    private static void logFailure(HttpServletRequest request, StandardError body, List<Throwable> causes) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String details = causes.stream().limit(12).map(ResourceExceptionHandler::safeDiagnostic)
                .collect(Collectors.joining(" <- "));
        if (body.getStatus() == 500) {
            diagnostics.error("API error id={} time={} code={} route={} causes={}", body.getCorrelationId(),
                    body.getTimestamp(), body.getCode(), route, details);
        } else {
            diagnostics.warn("API error id={} time={} code={} route={} causes={}", body.getCorrelationId(),
                    body.getTimestamp(), body.getCode(), route, details);
        }
    }

    private static String safeDiagnostic(Throwable cause) {
        String sql = "";
        if (cause instanceof SQLException exception) {
            String state = exception.getSQLState();
            sql = " [state=" + (state != null && state.matches("[0-9A-Z]{5}") ? state : "unknown")
                    + ", vendor=" + exception.getErrorCode() + "]";
        }
        String frames = Arrays.stream(cause.getStackTrace()).limit(5)
                .map(StackTraceElement::toString).collect(Collectors.joining(", "));
        return cause.getClass().getName() + sql + " at " + frames;
    }
}

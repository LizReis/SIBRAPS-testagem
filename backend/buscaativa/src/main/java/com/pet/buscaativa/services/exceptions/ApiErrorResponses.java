package com.pet.buscaativa.services.exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiErrorResponses {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ATTRIBUTE = ApiErrorResponses.class.getName() + ".correlationId";

    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    private static final String AUTHENTICATION_MESSAGE = "Autenticação válida é necessária para acessar este recurso.";
    private static final String ACCESS_DENIED_MESSAGE = "Você não tem permissão para realizar esta ação.";

    private final ObjectMapper json;

    public ApiErrorResponses(ObjectMapper json) {
        this.json = json;
    }

    public static StandardError authenticationRequired(HttpServletRequest request) {
        return body(request, 401, AUTHENTICATION_REQUIRED, "Não autorizado.", AUTHENTICATION_MESSAGE);
    }

    public static StandardError accessDenied(HttpServletRequest request) {
        return body(request, 403, ACCESS_DENIED, "Acesso negado.", ACCESS_DENIED_MESSAGE);
    }

    static StandardError body(HttpServletRequest request, int status, String code, String error, String message) {
        var body = new StandardError(Instant.now(), status, error, message, request.getRequestURI());
        body.setCode(code);
        body.setCorrelationId(correlationId(request));
        return body;
    }

    public static String correlationId(HttpServletRequest request) {
        if (request.getAttribute(CORRELATION_ATTRIBUTE) instanceof String id) {
            return id;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ATTRIBUTE, id);
        return id;
    }

    public void write(HttpServletResponse response, StandardError body) throws IOException {
        response.setStatus(body.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CORRELATION_HEADER, body.getCorrelationId());
        response.getWriter().write(json.writeValueAsString(body));
    }
}

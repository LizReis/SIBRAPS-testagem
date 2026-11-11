package com.pet.buscaativa.controllers;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import com.pet.buscaativa.services.exceptions.PendenciaFrequenciaException;
import lombok.RequiredArgsConstructor;

/** Narrow API adapter; global error classification is deliberately unchanged. */
@RestControllerAdvice(assignableTypes={AgendamentoController.class,GrupoTerapeuticoController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PendenciaFrequenciaHandler {
    private final Clock clock;
    public record PendenciaDTO(Instant timestamp,int status,String error,String message,String path,
            String codigo,String tipoConflito,Long agendamentoId,Long sessaoId){}
    @ExceptionHandler(PendenciaFrequenciaException.class)
    public ResponseEntity<PendenciaDTO> pendencia(PendenciaFrequenciaException e,HttpServletRequest request) {
        return ResponseEntity.status(409).body(new PendenciaDTO(clock.instant(),409,"Conflito de regra de negócio.",
            e.getMessage(),request.getRequestURI(),e.getCodigo(),e.getTipoConflito(),e.getAgendamentoId(),e.getSessaoId()));
    }
}

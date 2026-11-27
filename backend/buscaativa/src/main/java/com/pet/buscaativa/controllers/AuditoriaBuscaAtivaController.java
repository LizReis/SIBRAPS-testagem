package com.pet.buscaativa.controllers;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.pet.buscaativa.services.exceptions.ApiErrorResponses;
import com.pet.buscaativa.services.exceptions.StandardError;
import java.time.Instant;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.services.AuditoriaBuscaAtivaService;
import lombok.RequiredArgsConstructor;

@org.springframework.context.annotation.Profile("test")
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class AuditoriaBuscaAtivaController {
    private final AuditoriaBuscaAtivaService service;
    @PostMapping("/pacientes/{id}/alertas/visualizacoes") @PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')")
    public ResponseEntity<AuditoriaBuscaAtivaDTO> visualizar(@PathVariable UUID id,@Valid @RequestBody VisualizacaoAlertaDTO dto) {
        var result=service.registrarVisualizacao(id,dto);return ResponseEntity.status(result.criado()?201:200).body(result.evento());
    }
    @PostMapping("/pacientes/{id}/alertas/encaminhamentos") @PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
    public ResponseEntity<AuditoriaBuscaAtivaDTO> encaminhar(@PathVariable UUID id,@Valid @RequestBody EncaminhamentoAlertaDTO dto) {
        var result=service.registrarEncaminhamento(id,dto);return ResponseEntity.status(result.criado()?201:200).body(result.evento());
    }
    @GetMapping("/pacientes/{id}/alertas/auditoria") @PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
    public Page<AuditoriaBuscaAtivaDTO> paciente(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {return service.listarPaciente(id,page,size);}
    @GetMapping("/alertas/auditoria") @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Page<AuditoriaBuscaAtivaDTO> unidade(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {return service.listarUnidade(page,size);}
    // This API has historically rejected malformed audit input with 422.
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<StandardError> corpoInvalido(HttpServletRequest request) {
        var body = new StandardError(Instant.now(), 422, "Dados inválidos.",
                "Informe um corpo JSON válido com os campos previstos no contrato.", request.getRequestURI());
        body.setCode("MALFORMED_REQUEST");
        body.setCorrelationId(ApiErrorResponses.correlationId(request));
        return ResponseEntity.unprocessableEntity()
                .header(ApiErrorResponses.CORRELATION_HEADER, body.getCorrelationId()).body(body);
    }
}

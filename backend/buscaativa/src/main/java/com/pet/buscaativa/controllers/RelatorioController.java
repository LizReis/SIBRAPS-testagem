package com.pet.buscaativa.controllers;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.RelatorioService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/relatorios")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'PROFISSIONAL', 'RECEPCAO')")
public class RelatorioController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final RelatorioService relatorioService;

    @GetMapping(value = "/busca-ativa/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> buscaAtiva(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento) {
        return pdf(relatorioService.gerarBuscaAtiva(dataInicio, dataFim, profissionalId, tipoAcompanhamento),
                "relatorio-busca-ativa-" + LocalDate.now() + ".pdf");
    }

    @GetMapping(value = "/frequencia-grupos/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> frequenciaGrupos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) Long grupoId) {
        return pdf(relatorioService.gerarFrequenciaGrupos(dataInicio, dataFim, grupoId),
                "relatorio-frequencia-grupos-" + LocalDate.now() + ".pdf");
    }

    @GetMapping(value = "/busca-ativa/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> buscaAtivaExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento) {
        return excel(relatorioService.gerarBuscaAtivaExcel(dataInicio, dataFim, profissionalId, tipoAcompanhamento),
                "relatorio-busca-ativa-" + LocalDate.now() + ".xlsx");
    }

    @GetMapping(value = "/frequencia-grupos/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> frequenciaGruposExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim,
            @RequestParam(required = false) Long grupoId) {
        return excel(relatorioService.gerarFrequenciaGruposExcel(dataInicio, dataFim, grupoId),
                "relatorio-frequencia-grupos-" + LocalDate.now() + ".xlsx");
    }

    private ResponseEntity<byte[]> pdf(byte[] conteudo, String nome) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(nome).build());
        headers.setContentLength(conteudo.length);
        return ResponseEntity.ok().headers(headers).body(conteudo);
    }

    private ResponseEntity<byte[]> excel(byte[] conteudo, String nome) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment().filename(nome).build());
        headers.setContentLength(conteudo.length);
        return ResponseEntity.ok().headers(headers).body(conteudo);
    }
}
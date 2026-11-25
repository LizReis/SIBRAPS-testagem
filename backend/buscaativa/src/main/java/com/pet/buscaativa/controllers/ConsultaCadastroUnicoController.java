package com.pet.buscaativa.controllers;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.services.impl.ConsultaCadastralExataService;

import lombok.RequiredArgsConstructor;

/**
 * Primeiro contrato HTTP do cadastro unico, somente de pre-conferencia.
 *
 * CPF/CNS chegam no CORPO do POST (nunca na URL/query string); a resposta
 * contem APENAS nome, nome da mae e data de nascimento, sem UUID, status,
 * historicos, unidade ou vinculos. O servico confere novamente a unidade
 * autentica e a identidade documental antes de qualquer consulta global.
 *
 * Esta rota NAO registra paciente, NAO cria vinculo e NAO concede acesso
 * clinico de outra unidade. A confirmacao/vinculacao permanecem internas,
 * ate o corte coordenado dos filtros legados.
 */
@org.springframework.context.annotation.Profile("test")
@RestController
@RequestMapping("/api/pacientes/cadastro-unico")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL')")
public class ConsultaCadastroUnicoController {
    private final ConsultaCadastralExataService consulta;

    public record DocumentosConsulta(String cpf, String cns) {}

    @PostMapping("/conferencia")
    public ResponseEntity<ConsultaCadastralExataService.IdentidadeCadastral> conferir(
            @RequestBody DocumentosConsulta documentos) {
        var identidade = consulta.localizar(documentos.cpf(), documentos.cns());
        // Proibir o cache intermediario dos dados pessoais retornados.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(identidade);
    }
}

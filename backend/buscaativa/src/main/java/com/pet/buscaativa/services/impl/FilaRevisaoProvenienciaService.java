package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Leitura INTERNA e estritamente local da futura fila de revisao em Relatorios.
 *
 * Nao disponibiliza endpoint, tela, aprovacao, rejeicao ou backfill.
 * So o administrador da propria unidade obtem as pendencias locais.
 * A chave canonica vem do usuario autenticado, nunca da requisicao.
 * Metadados da fila nao sao autorizacao para consultar um fato clinico:
 * detalhes continuam exigindo verificacao por paciente/vinculo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FilaRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final FilaPendenciasRevisaoProvenienciaRepository fila;

    /** Exibe somente os identificadores minimos necessarios para triagem local. */
    public record Item(
            UUID id,
            Long pacienteId,
            TipoRegistro tipoRegistro,
            Situacao situacaoEvidencia,
            Instant solicitadoEm,
            Estado estado) {}

    /** Detalhes exclusivos da solicitacao; nao expor prontuario ou autor. */
    public record Detalhe(
            UUID id,
            Long pacienteId,
            TipoRegistro tipoRegistro,
            Long registroId,
            Situacao situacaoEvidencia,
            String justificativa,
            Instant solicitadoEm,
            Estado estado) {}

    public Page<Item> listarPendentes(Pageable pagina) {
        String unidade = unidadeDoAdministrador();
        if (pagina == null || !pagina.isPaged()
                || pagina.getPageSize() < 1 || pagina.getPageSize() > 100)
            throw new IllegalArgumentException("Paginacao limitada a 1..100 registros.");
        return fila.findByUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                unidade, Estado.PENDENTE, pagina)
                .map(FilaRevisaoProvenienciaService::resumir);
    }

    /**
     * Confere autorizacao ANTES de consultar um ticket por UUID.
     * Mesmo o UUID de outra unidade devolve not found, sem metadados.
     * Nao carrega nem autoriza o fato clinico a que o ticket se refere.
     */
    public Detalhe detalharPendente(UUID id) {
        String unidade = unidadeDoAdministrador();
        if (id == null)
            throw new ResourceNotFoundException("Pendencia nao encontrada.");
        return fila.findAbertaByIdAndUnidadeChaveAndEstado(id, unidade, Estado.PENDENTE)
                .map(FilaRevisaoProvenienciaService::detalhar)
                .orElseThrow(() -> new ResourceNotFoundException("Pendencia nao encontrada."));
    }

    private String unidadeDoAdministrador() {
        var usuario = escopo.usuarioLogado();
        if (usuario == null || usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para a fila de revisao.");
        escopo.validarUnidadeConfigurada();
        return ChaveUnidadeAssistencial.doUsuarioAtual(usuario);
    }

    private static Detalhe detalhar(PendenciaRevisaoProveniencia p) {
        return new Detalhe(p.getId(), p.getPacienteId(), p.getTipoRegistro(),
                p.getRegistroId(), p.getSituacaoEvidencia(), p.getJustificativa(),
                p.getSolicitadoEm(), p.getEstado());
    }

    private static Item resumir(PendenciaRevisaoProveniencia pendencia) {
        return new Item(pendencia.getId(), pendencia.getPacienteId(),
                pendencia.getTipoRegistro(), pendencia.getSituacaoEvidencia(),
                pendencia.getSolicitadoEm(), pendencia.getEstado());
    }
}

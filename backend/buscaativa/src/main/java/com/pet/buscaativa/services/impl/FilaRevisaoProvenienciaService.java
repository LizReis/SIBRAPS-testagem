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

    public Page<Item> listarPendentes(Pageable pagina) {
        var usuario = escopo.usuarioLogado();
        if (usuario == null || usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissao para a fila de revisao.");
        if (pagina == null || !pagina.isPaged()
                || pagina.getPageSize() < 1 || pagina.getPageSize() > 100)
            throw new IllegalArgumentException("Paginacao limitada a 1..100 registros.");

        escopo.validarUnidadeConfigurada();
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(usuario);
        return fila.findByUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                unidade, Estado.PENDENTE, pagina)
                .map(FilaRevisaoProvenienciaService::resumir);
    }

    private static Item resumir(PendenciaRevisaoProveniencia pendencia) {
        return new Item(pendencia.getId(), pendencia.getPacienteId(),
                pendencia.getTipoRegistro(), pendencia.getSituacaoEvidencia(),
                pendencia.getSolicitadoEm(), pendencia.getEstado());
    }
}

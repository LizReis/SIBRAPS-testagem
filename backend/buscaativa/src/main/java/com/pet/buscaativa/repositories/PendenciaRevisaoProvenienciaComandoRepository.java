package com.pet.buscaativa.repositories;

import java.util.UUID;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;

/**
 * Contrato interno e restrito de escrita para solicitacoes V12.
 *
 * NAO e um endpoint. Usar somente a partir do servico transacional que
 * verifica perfil, identidade do paciente, vinculo e origem local do fato.
 * Nao oferece findAll, delete ou update: solicitacoes sao append-only.
 *
 * A consulta de duplicidade, isoladamente, NAO e barreira de concorrencia:
 * o servico primeiro bloqueia o vinculo local em transacao.
 */
public interface PendenciaRevisaoProvenienciaComandoRepository
        extends Repository<PendenciaRevisaoProveniencia, UUID> {

    @Transactional
    <S extends PendenciaRevisaoProveniencia> S save(S pendencia);

    @Transactional(readOnly = true)
    boolean existsByPacienteIdAndUnidadeChaveAndTipoRegistroAndRegistroId(
            Long pacienteId, String unidadeChave, TipoRegistro tipoRegistro, Long registroId);
}

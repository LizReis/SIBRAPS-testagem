package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;

/**
 * Acesso INTERNO e estritamente delimitado as pendencias de proveniencia.
 *
 * Esta primeira integracao da V12 e somente de leitura. Nao ha operacoes
 * save/delete, listagem global ou busca sem paciente E unidade; nenhuma
 * rota HTTP, painel ou processo de conciliacao e ativado aqui.
 *
 * Os filtros sao defesa em profundidade, NAO substituem a autorizacao do
 * servico chamador. Nem o UUID de uma pendencia nem o cadastro global do
 * paciente concedem acesso a outra unidade.
 */
@Transactional(readOnly = true)
public interface PendenciaRevisaoProvenienciaRepository
        extends Repository<PendenciaRevisaoProveniencia, UUID> {

    /** Busca uma unica pendencia somente dentro do paciente e da unidade autorizados. */
    Optional<PendenciaRevisaoProveniencia> findByIdAndPacienteIdAndUnidadeChave(
            UUID id, Long pacienteId, String unidadeChave);

    /** Pagina as pendencias de um paciente na unidade, sem expor outras unidades. */
    Page<PendenciaRevisaoProveniencia> findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
            Long pacienteId, String unidadeChave, Estado estado, Pageable pagina);
}

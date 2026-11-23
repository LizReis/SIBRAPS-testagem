package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;

/**
 * Acesso INTERNO e estritamente delimitado as pendencias de proveniencia.
 *
 * As leituras exigem paciente E unidade. Este repositorio nao oferece
 * escrita. Operacoes de comando ficam em contrato interno distinto; nenhuma
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
    @Query("""
            select p from PendenciaRevisaoProveniencia p
            where p.id = :id and p.pacienteId = :pacienteId
              and p.unidadeChave = :unidadeChave
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            """)
    Optional<PendenciaRevisaoProveniencia> findByIdAndPacienteIdAndUnidadeChave(
            @Param("id") UUID id, @Param("pacienteId") Long pacienteId,
            @Param("unidadeChave") String unidadeChave);

    /** Pagina as pendencias de um paciente na unidade, sem expor outras unidades. */
    @Query(value = """
            select p from PendenciaRevisaoProveniencia p
            where p.pacienteId = :pacienteId and p.unidadeChave = :unidadeChave
              and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            order by p.solicitadoEm desc, p.id desc
            """, countQuery = """
            select count(p) from PendenciaRevisaoProveniencia p
            where p.pacienteId = :pacienteId and p.unidadeChave = :unidadeChave
              and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            """)
    Page<PendenciaRevisaoProveniencia> findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
            @Param("pacienteId") Long pacienteId, @Param("unidadeChave") String unidadeChave,
            @Param("estado") Estado estado, Pageable pagina);
}

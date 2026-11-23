package com.pet.buscaativa.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;

/**
 * Contrato interno e somente de LEITURA dos eventos V13.
 * Nenhum acesso global, escrita, alteracao ou exclusao.
 * O chamador deve conferir autorizacao e derivar paciente/unidade do
 * vinculo local autenticado; o UUID nao autoriza consulta por si so.
 */
@Transactional(readOnly = true)
public interface DecisaoRevisaoProvenienciaRepository
        extends Repository<DecisaoRevisaoProveniencia, UUID> {

    Optional<DecisaoRevisaoProveniencia> findByIdAndPacienteIdAndUnidadeChave(
            UUID id, Long pacienteId, String unidadeChave);

    Optional<DecisaoRevisaoProveniencia> findByPendenciaIdAndPacienteIdAndUnidadeChave(
            UUID pendenciaId, Long pacienteId, String unidadeChave);

    /**
     * Historico imutavel das decisoes, isolado por paciente E unidade.
     * Ordenacao com desempate estavel para paginas consecutivas.
     * Nunca oferecer findAll ou consulta apenas por identidade global.
     */
    @Query(value = """
            select d from DecisaoRevisaoProveniencia d
            where d.pacienteId = :pacienteId and d.unidadeChave = :unidadeChave
            order by d.decididaEm desc, d.id desc
            """, countQuery = """
            select count(d) from DecisaoRevisaoProveniencia d
            where d.pacienteId = :pacienteId and d.unidadeChave = :unidadeChave
            """)
    Page<DecisaoRevisaoProveniencia> listarPorPacienteEUnidade(
            @Param("pacienteId") Long pacienteId,
            @Param("unidadeChave") String unidadeChave, Pageable pagina);
}

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
 * Consulta interna exclusiva da fila de pendencias de UMA unidade.
 * Sem findAll, busca por ticket sem escopo, escrita ou operacoes de decisao.
 * A chave da unidade deve vir do servico autenticado, nunca de parametros HTTP.
 */
@Transactional(readOnly = true)
public interface FilaPendenciasRevisaoProvenienciaRepository
        extends Repository<PendenciaRevisaoProveniencia, UUID> {

    /**
     * A solicitacao V12 fica PENDENTE para sempre (append-only); uma decisao
     * V13 e outro evento. Na fila aberta, excluir somente as solicitacoes
     * com decisao persistida, sem atualizar a solicitacao clinica original.
     */
    @Query(value = """
            select p from PendenciaRevisaoProveniencia p
            where p.unidadeChave = :unidadeChave and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            order by p.solicitadoEm desc, p.id desc
            """, countQuery = """
            select count(p) from PendenciaRevisaoProveniencia p
            where p.unidadeChave = :unidadeChave and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            """)
    Page<PendenciaRevisaoProveniencia> findByUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
            @Param("unidadeChave") String unidadeChave, @Param("estado") Estado estado,
            Pageable pagina);

    /**
     * Contagem local para o futuro painel, sem percorrer registros/DTOs.
     * Tickets com uma decisao imutavel V13 nao sao pendencias abertas.
     */
    @Query("""
            select count(p) from PendenciaRevisaoProveniencia p
            where p.unidadeChave = :unidadeChave and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            """)
    long contarAbertasDaUnidade(@Param("unidadeChave") String unidadeChave,
            @Param("estado") Estado estado);

    /** Detalhe apenas da propria unidade e somente enquanto PENDENTE. */
    Optional<PendenciaRevisaoProveniencia> findByIdAndUnidadeChaveAndEstado(
            UUID id, String unidadeChave, Estado estado);

    /**
     * Exclusivo para exibir o detalhe da fila aberta; NAO substituir o
     * metodo acima, utilizado pelo comando para detectar decisao duplicada.
     */
    @Query("""
            select p from PendenciaRevisaoProveniencia p
            where p.id = :id and p.unidadeChave = :unidadeChave and p.estado = :estado
              and not exists (
                  select d.id from DecisaoRevisaoProveniencia d
                  where d.pendencia = p)
            """)
    Optional<PendenciaRevisaoProveniencia> findAbertaByIdAndUnidadeChaveAndEstado(
            @Param("id") UUID id, @Param("unidadeChave") String unidadeChave,
            @Param("estado") Estado estado);
}

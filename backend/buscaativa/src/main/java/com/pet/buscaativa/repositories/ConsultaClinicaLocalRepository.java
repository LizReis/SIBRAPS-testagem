package com.pet.buscaativa.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Projecoes MINIMAS para caracterizar consultas clinicas do escopo local.
 *
 * Uso INTERNO por LeituraClinicaLocalService, apos exigir vinculo da unidade
 * autenticada. Nenhum metodo retorna atributos pessoais ou fatos clinicos.
 * A unidade NAO pode vir de parametro HTTP fornecido pelo cliente.
 *
 * Nao substitui o escopo dos endpoints legados ate migracao coordenada.
 */
@Repository
@RequiredArgsConstructor
public class ConsultaClinicaLocalRepository {
    private final EntityManager em;

    public List<Long> agendamentos(UUID pacienteId, String unidade) {
        return em.createQuery("""
                select a.id from Agendamento a
                where a.paciente.idPublico = :pacienteId
                  and a.unidadeOrigemChave = :unidade
                order by a.dataAgendamento desc, a.id desc
                """, Long.class)
                .setParameter("pacienteId", pacienteId)
                .setParameter("unidade", unidade)
                .getResultList();
    }

    /**
     * Exigir origem do proprio evento, consistencia de qualquer fonte, e,
     * para frequencia em grupo, participacao real do paciente naquela sessao.
     * Historico manual local nao possui fonte de agenda ou sessao.
     */
    public List<Long> historicos(UUID pacienteId, String unidade) {
        return em.createQuery("""
                select h.id from HistoricoPaciente h
                left join h.agendamento a
                left join h.sessaoGrupo s
                left join s.grupo g
                where h.paciente.idPublico = :pacienteId
                  and h.unidadeOrigemChave = :unidade
                  and (a.id is null or
                       (a.unidadeOrigemChave = :unidade
                        and a.paciente.idPublico = :pacienteId))
                  and (s.id is null or
                       (s.unidadeOrigemChave = :unidade
                        and g.unidadeOrigemChave = :unidade
                        and exists (
                            select sp.id from SessaoGrupoParticipante sp
                            where sp.sessaoGrupo = s and sp.paciente = h.paciente
                        )))
                  and (a.id is null or s.id is null)
                order by h.ocorridoEm desc, h.id desc
                """, Long.class)
                .setParameter("pacienteId", pacienteId)
                .setParameter("unidade", unidade)
                .getResultList();
    }

    /** ID da sessao somente se ha participacao da pessoa e origem local. */
    public List<Long> sessoesComParticipacao(UUID pacienteId, String unidade) {
        return em.createQuery("""
                select s.id from SessaoGrupoParticipante part
                join part.sessaoGrupo s
                join s.grupo g
                where part.paciente.idPublico = :pacienteId
                  and s.unidadeOrigemChave = :unidade
                  and g.unidadeOrigemChave = :unidade
                order by s.dataSessao desc, s.id desc
                """, Long.class)
                .setParameter("pacienteId", pacienteId)
                .setParameter("unidade", unidade)
                .getResultList();
    }
}

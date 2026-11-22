package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Caracteriza a ORDEM dos fatos de frequencia do vinculo assistencial local.
 *
 * Somente leitura, sem endpoints e sem atualizar Paciente ou VinculoAssistencial.
 * Nao calcula faltas consecutivas, risco, ultima presenca, visita domiciliar,
 * busca ativa ou historicos. Historicos sem origem comprovada nunca sao
 * atribuidos automaticamente pela referencia da pessoa ou lotacao de usuario.
 *
 * A ordem individual antes de grupo para horarios exatamente iguais reproduz
 * o desempate da rotina legada, mas ainda exige avaliacao clinica antes do corte.
 */
@Service
@RequiredArgsConstructor
public class LinhaDoTempoFrequenciasVinculoService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EntityManager em;

    public enum Fonte { ATENDIMENTO_INDIVIDUAL, SESSAO_GRUPO }

    public record Evento(LocalDateTime ocorridoEm, boolean presente, Fonte fonte, Long registroId) {}

    /**
     * 'Fatos sem origem' indica incerteza sobre a proveniencia do paciente
     * global, nao prova que tais fatos pertencam a este vinculo.
     * Nao utilizar esta linha do tempo em decisoes clinicas enquanto houver
     * fatos ambiguos; os fatos nao devem desaparecer de uma revisao humana.
     */
    public record Diagnostico(List<Evento> eventosComOrigemLocal,
            long agendamentosSemOrigemComFrequencia,
            long presencasGrupoSemOrigem) {
        public boolean haEvidenciaHistoricaSemUnidadeComprovada() {
            return agendamentosSemOrigemComFrequencia > 0 || presencasGrupoSemOrigem > 0;
        }
    }

    @Transactional(readOnly = true)
    public Diagnostico analisar(UUID pacienteId) {
        // Autorizacao fail-closed. Nao aceitar unidade por parametro externo.
        String unidade = autorizacao.exigirVinculoLocal(pacienteId).getUnidadeChave();
        List<Evento> eventos = new ArrayList<>();

        var frequencias = List.of(FrequenciaAssistencial.PRESENTE, FrequenciaAssistencial.FALTOU);
        List<Agendamento> agendamentos = em.createQuery("""
                select a from Agendamento a
                where a.paciente.idPublico = :paciente
                  and a.unidadeOrigemChave = :unidade
                  and a.migracaoPendente = false
                  and a.frequenciaAssistencial in :frequencias
                  and a.dataAgendamento is not null
                  and a.horaAtendimento is not null
                """, Agendamento.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("frequencias", frequencias)
                .getResultList();

        for (Agendamento a : agendamentos) {
            eventos.add(new Evento(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()),
                    a.getFrequenciaAssistencial() == FrequenciaAssistencial.PRESENTE,
                    Fonte.ATENDIMENTO_INDIVIDUAL, a.getId()));
        }

        var frequenciasGrupo = List.of(StatusPresencaGrupo.PRESENTE, StatusPresencaGrupo.FALTOU);
        List<SessaoGrupoParticipante> presencasGrupo = em.createQuery("""
                select p from SessaoGrupoParticipante p
                join fetch p.sessaoGrupo s
                join fetch s.grupo g
                where p.paciente.idPublico = :paciente
                  and s.unidadeOrigemChave = :unidade
                  and g.unidadeOrigemChave = :unidade
                  and s.status = :realizada
                  and p.statusPresenca in :frequencias
                  and s.dataSessao is not null
                  and s.horario is not null
                """, SessaoGrupoParticipante.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("realizada", StatusSessaoGrupo.REALIZADA)
                .setParameter("frequencias", frequenciasGrupo)
                .getResultList();

        for (SessaoGrupoParticipante p : presencasGrupo) {
            var sessao = p.getSessaoGrupo();
            eventos.add(new Evento(LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario()),
                    p.getStatusPresenca() == StatusPresencaGrupo.PRESENTE,
                    Fonte.SESSAO_GRUPO, p.getId()));
        }

        eventos.sort(Comparator.comparing(Evento::ocorridoEm)
                .thenComparingInt(e -> e.fonte() == Fonte.ATENDIMENTO_INDIVIDUAL ? 0 : 1)
                .thenComparing(Evento::registroId));

        // Fatos antigos da mesma identidade sem unidade comprovada NAO entram
        // na linha do tempo local, mas tambem NAO podem ser ignorados numa
        // futura decisao clinica: relatar a pendencia sem escolher a unidade.
        long agendamentosSemOrigem = em.createQuery("""
                select count(a) from Agendamento a
                where a.paciente.idPublico = :paciente
                  and a.unidadeOrigemChave is null
                  and (a.migracaoPendente = true
                       or a.frequenciaAssistencial in :frequencias)
                """, Long.class)
                .setParameter("paciente", pacienteId)
                .setParameter("frequencias", frequencias)
                .getSingleResult();

        long gruposSemOrigem = em.createQuery("""
                select count(p) from SessaoGrupoParticipante p
                join p.sessaoGrupo s
                join s.grupo g
                where p.paciente.idPublico = :paciente
                  and s.status = :realizada
                  and p.statusPresenca in :frequencias
                  and (s.unidadeOrigemChave is null or g.unidadeOrigemChave is null)
                """, Long.class)
                .setParameter("paciente", pacienteId)
                .setParameter("realizada", StatusSessaoGrupo.REALIZADA)
                .setParameter("frequencias", frequenciasGrupo)
                .getSingleResult();

        return new Diagnostico(List.copyOf(eventos), agendamentosSemOrigem, gruposSemOrigem);
    }
}

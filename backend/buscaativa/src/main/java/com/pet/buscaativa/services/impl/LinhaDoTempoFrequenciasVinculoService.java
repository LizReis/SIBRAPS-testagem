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
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.repositories.AgendamentoRepository;
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
    private final AgendamentoRepository agendaRepositorio;

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
            long presencasGrupoSemOrigem,
            long frequenciasInconsistentes,
            long agendamentosLocaisPendentesDeEvidencia) {
        public boolean haEvidenciaHistoricaSemUnidadeComprovada() {
            return agendamentosSemOrigemComFrequencia > 0 || presencasGrupoSemOrigem > 0;
        }
    }

    @Transactional(readOnly = true)
    public Diagnostico analisar(UUID pacienteId) {
        return analisar(autorizacao.exigirVinculoLocal(pacienteId));
    }

    // Usado pelo diagnóstico da unidade e pelo ensaio interno sobre vínculos persistidos.
    Diagnostico analisar(VinculoAssistencial vinculo) {
        Long pacienteId = vinculo.getPaciente().getId();
        String unidade = vinculo.getUnidadeChave();
        List<Evento> eventos = new ArrayList<>();
        long agendamentosSemOrigem = 0;
        long gruposSemOrigem = 0;
        long inconsistentes = 0;
        long agendamentosLocaisPendentes = 0;

        var frequencias = List.of(FrequenciaAssistencial.PRESENTE, FrequenciaAssistencial.FALTOU);
        List<Agendamento> agendamentos = em.createQuery("""
                select a from Agendamento a
                where a.paciente.id = :paciente
                  and (a.unidadeOrigemChave = :unidade or a.unidadeOrigemChave is null
                       or trim(a.unidadeOrigemChave) = '')
                  and (a.migracaoPendente = true or a.migracaoPendente is null or a.situacaoReserva is null
                       or a.frequenciaAssistencial is null or a.frequenciaAssistencial in :frequencias)
                """, Agendamento.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("frequencias", frequencias)
                .getResultList();

        for (Agendamento a : agendamentos) {
            var origem = AvaliacaoProvenienciaClinica.cadeiaAgendamento(a, pacienteId, unidade,
                    (original, sucessor) -> original.getId() == null || sucessor.getId() == null
                            || agendaRepositorio.existsByAgendamentoOriginal_IdAndIdNot(
                                    original.getId(), sucessor.getId()));
            boolean origemAusente = AvaliacaoProvenienciaClinica.origemAusente(a.getUnidadeOrigemChave());
            if (origemAusente || origem == AvaliacaoProvenienciaClinica.Situacao.SEM_ORIGEM_COMPROVADA) {
                agendamentosSemOrigem++;
                if (!origemAusente) agendamentosLocaisPendentes++;
            } else if (origem == AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE
                    || !AvaliacaoProvenienciaClinica.frequenciaIndividualRegistrada(a)) {
                agendamentosLocaisPendentes++;
                inconsistentes++;
            } else {
                eventos.add(new Evento(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()),
                        a.getFrequenciaAssistencial() == FrequenciaAssistencial.PRESENTE,
                        Fonte.ATENDIMENTO_INDIVIDUAL, a.getId()));
            }
        }

        List<SessaoGrupoParticipante> participacoes = em.createQuery("""
                select p from SessaoGrupoParticipante p
                join fetch p.sessaoGrupo s
                join fetch s.grupo g
                where p.paciente.id = :paciente
                  and (s.unidadeOrigemChave = :unidade or g.unidadeOrigemChave = :unidade
                       or s.unidadeOrigemChave is null or trim(s.unidadeOrigemChave) = ''
                       or g.unidadeOrigemChave is null or trim(g.unidadeOrigemChave) = '')
                  and (s.status = :realizada or s.status is null
                       or p.statusPresenca is null or p.statusPresenca in :frequencias)
                """, SessaoGrupoParticipante.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("realizada", StatusSessaoGrupo.REALIZADA)
                .setParameter("frequencias", List.of(StatusPresencaGrupo.PRESENTE, StatusPresencaGrupo.FALTOU))
                .getResultList();

        for (SessaoGrupoParticipante p : participacoes) {
            var sessao = p.getSessaoGrupo();
            var origem = AvaliacaoProvenienciaClinica.origemSessao(sessao);
            if (origem == AvaliacaoProvenienciaClinica.Situacao.SEM_ORIGEM_COMPROVADA) {
                gruposSemOrigem++;
            } else if (origem == AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE
                    || sessao.getStatus() != StatusSessaoGrupo.REALIZADA
                    || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca())) {
                inconsistentes++;
            } else {
                eventos.add(new Evento(LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario()),
                        p.getStatusPresenca() == StatusPresencaGrupo.PRESENTE,
                        Fonte.SESSAO_GRUPO, p.getId()));
            }
        }

        eventos.sort(Comparator.comparing(Evento::ocorridoEm)
                .thenComparingInt(e -> e.fonte() == Fonte.ATENDIMENTO_INDIVIDUAL ? 0 : 1)
                .thenComparing(Evento::registroId));
        return new Diagnostico(List.copyOf(eventos), agendamentosSemOrigem, gruposSemOrigem,
                inconsistentes, agendamentosLocaisPendentes);
    }
}

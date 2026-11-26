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

    record Evidencias(Diagnostico diagnostico, List<Agendamento> agenda,
            List<SessaoGrupoParticipante> participacoes, boolean agendaLocalInconclusiva,
            Diagnostico diagnosticoBruto, List<Agendamento> agendaBruta,
            List<SessaoGrupoParticipante> participacoesBrutas,
            java.util.Set<ProjecaoResolucaoFrequencia.Chave> desconsiderados,
            boolean resolucaoInconclusiva) {
        Evidencias(Diagnostico diagnostico,List<Agendamento> agenda,
                List<SessaoGrupoParticipante> participacoes,boolean agendaLocalInconclusiva) {
            this(diagnostico,agenda,participacoes,agendaLocalInconclusiva,diagnostico,agenda,participacoes,
                    java.util.Set.of(),false);
        }
        boolean desconsiderado(Fonte fonte,Long id) {
            return desconsiderados.contains(new ProjecaoResolucaoFrequencia.Chave(fonte,id));
        }
    }

    Diagnostico analisar(VinculoAssistencial vinculo) {
        return examinarEfetivo(vinculo, false).diagnostico();
    }

    /**
     * Somente consumidores clínicos usam a projeção. A preparação de novas
     * resoluções continua conferindo os fatos brutos por examinar().
     */
    Evidencias examinarEfetivo(VinculoAssistencial vinculo, boolean incluirReservas) {
        // Carregar responsáveis e cadeia mesmo quando o consumidor pede
        // apenas a sequência. Sem V24 o comportamento anterior é preservado.
        boolean instalada=((Number)em.createNativeQuery("""
                select count(*) from information_schema.tables
                where table_schema=current_schema() and table_name='VW_RESOLUCAO_FREQUENCIA_ATIVA'
                """).getSingleResult()).longValue()==1;
        if(!instalada) return examinar(vinculo,incluirReservas);
        var bruto=examinar(vinculo,true);
        @SuppressWarnings("unchecked")
        List<Object[]> linhas=em.createNativeQuery("""
                select fonte_valida,fato_valido,fonte_desconsiderada,fato_desconsiderado,
                       horario,frequencia_valida,frequencia_desconsiderada,responsavel_valido,responsavel_desconsiderado
                from vw_resolucao_frequencia_ativa
                where vinculo_id=:vinculo and paciente_id=:paciente and unidade_chave=:unidade
                """).setParameter("vinculo",vinculo.getId())
                .setParameter("paciente",vinculo.getPaciente().getId())
                .setParameter("unidade",vinculo.getUnidadeChave()).getResultList();
        if(linhas.isEmpty()) return bruto;
        var resolucoes=linhas.stream().map(r->new ProjecaoResolucaoFrequencia.Resolucao(
                new ProjecaoResolucaoFrequencia.Chave(Fonte.valueOf(r[0].toString()),((Number)r[1]).longValue()),
                new ProjecaoResolucaoFrequencia.Chave(Fonte.valueOf(r[2].toString()),((Number)r[3]).longValue()),
                r[4] instanceof LocalDateTime t?t:((java.sql.Timestamp)r[4]).toLocalDateTime(),
                "PRESENTE".equals(r[5]),"PRESENTE".equals(r[6]),
                ((Number)r[7]).longValue(),((Number)r[8]).longValue())).toList();
        var responsaveis=new java.util.HashMap<ProjecaoResolucaoFrequencia.Chave,Long>();
        bruto.agenda().forEach(a->responsaveis.put(new ProjecaoResolucaoFrequencia.Chave(
                Fonte.ATENDIMENTO_INDIVIDUAL,a.getId()),a.getUsuario().getId()));
        bruto.participacoes().forEach(p->{
            var responsavel=p.getSessaoGrupo().getResponsavelAtendimento();
            if(responsavel!=null) responsaveis.put(new ProjecaoResolucaoFrequencia.Chave(
                    Fonte.SESSAO_GRUPO,p.getId()),responsavel.getId());
        });
        var d=bruto.diagnostico();
        var fatos=d.eventosComOrigemLocal().stream().map(e->new ProjecaoResolucaoFrequencia.Fato(e,
                responsaveis.get(new ProjecaoResolucaoFrequencia.Chave(e.fonte(),e.registroId())))).toList();
        var projecao=ProjecaoResolucaoFrequencia.aplicar(fatos,resolucoes);
        var excluidos=projecao.desconsiderados();
        var eventos=d.eventosComOrigemLocal().stream().filter(e->!excluidos.contains(
                new ProjecaoResolucaoFrequencia.Chave(e.fonte(),e.registroId()))).toList();
        var efetivo=new Diagnostico(eventos,d.agendamentosSemOrigemComFrequencia(),d.presencasGrupoSemOrigem(),
                d.frequenciasInconsistentes()+(projecao.inconclusivo()?1:0),d.agendamentosLocaisPendentesDeEvidencia());
        // RF22 exige tanto a falta original como a falta na remarcação válidas.
        // Retirar do conjunto de candidatos à visita também o sucessor cujo
        // original foi desconsiderado, sem remover esse sucessor da frequência.
        var agenda=bruto.agenda().stream().filter(a->!excluidos.contains(
                new ProjecaoResolucaoFrequencia.Chave(Fonte.ATENDIMENTO_INDIVIDUAL,a.getId()))
                && (a.getAgendamentoOriginal()==null || !excluidos.contains(
                new ProjecaoResolucaoFrequencia.Chave(Fonte.ATENDIMENTO_INDIVIDUAL,a.getAgendamentoOriginal().getId())))).toList();
        var participacoes=bruto.participacoes().stream().filter(p->!excluidos.contains(
                new ProjecaoResolucaoFrequencia.Chave(Fonte.SESSAO_GRUPO,p.getId()))).toList();
        return new Evidencias(efetivo,agenda,participacoes,bruto.agendaLocalInconclusiva(),
                d,bruto.agenda(),bruto.participacoes(),excluidos,projecao.inconclusivo());
    }

    void exigirSemResolucaoAtiva(VinculoAssistencial local,Fonte fonte,Long id) {
        boolean instalada=((Number)em.createNativeQuery("""
                select count(*) from information_schema.tables
                where table_schema=current_schema() and table_name='VW_RESOLUCAO_FREQUENCIA_ATIVA'
                """).getSingleResult()).longValue()==1;
        if(!instalada) return;
        var total=((Number)em.createNativeQuery("""
                select count(*) from vw_resolucao_frequencia_ativa
                where vinculo_id=:vinculo and paciente_id=:paciente and unidade_chave=:unidade
                  and ((fonte_valida=:fonte and fato_valido=:id)
                    or (fonte_desconsiderada=:fonte and fato_desconsiderado=:id))
                """).setParameter("vinculo",local.getId()).setParameter("paciente",local.getPaciente().getId())
                .setParameter("unidade",local.getUnidadeChave()).setParameter("fonte",fonte.name())
                .setParameter("id",id).getSingleResult()).longValue();
        if(total>0) throw new com.pet.buscaativa.services.exceptions.ConflictException(
                "Este fato participa de resolução ativa. Desfaça a resolução antes de corrigir a frequência.");
    }

    Evidencias examinar(VinculoAssistencial vinculo, boolean incluirReservas) {
        Long pacienteId = vinculo.getPaciente().getId();
        String unidade = vinculo.getUnidadeChave();
        List<Evento> eventos = new ArrayList<>();
        List<Agendamento> agenda = new ArrayList<>();
        List<SessaoGrupoParticipante> sessoes = new ArrayList<>();
        boolean agendaLocalInconclusiva = false;
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
                       or a.frequenciaAssistencial is null or a.frequenciaAssistencial in :frequencias
                       or (:incluirReservas = true and a.unidadeOrigemChave = :unidade))
                """, Agendamento.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("frequencias", frequencias)
                .setParameter("incluirReservas", incluirReservas)
                .getResultList();

        for (Agendamento a : agendamentos) {
            var origem = AvaliacaoProvenienciaClinica.cadeiaAgendamento(a, pacienteId, unidade,
                    (original, sucessor) -> original.getId() == null || sucessor.getId() == null
                            || agendaRepositorio.existsByAgendamentoOriginal_IdAndIdNot(
                                    original.getId(), sucessor.getId()));
            boolean origemAusente = AvaliacaoProvenienciaClinica.origemAusente(a.getUnidadeOrigemChave());
            if (incluirReservas && unidade.equals(a.getUnidadeOrigemChave())) {
                if (origem == AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) {
                    agenda.add(a);
                } else {
                    agendaLocalInconclusiva = true;
                }
                if (!AvaliacaoProvenienciaClinica.frequenciaIndividualEmAnalise(a)) continue;
            }
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
                       or p.statusPresenca is null or p.statusPresenca in :frequencias
                       or (:incluirReservas = true and s.unidadeOrigemChave = :unidade))
                """, SessaoGrupoParticipante.class)
                .setParameter("paciente", pacienteId)
                .setParameter("unidade", unidade)
                .setParameter("realizada", StatusSessaoGrupo.REALIZADA)
                .setParameter("incluirReservas", incluirReservas)
                .setParameter("frequencias", List.of(StatusPresencaGrupo.PRESENTE, StatusPresencaGrupo.FALTOU))
                .getResultList();

        for (SessaoGrupoParticipante p : participacoes) {
            var sessao = p.getSessaoGrupo();
            var origem = AvaliacaoProvenienciaClinica.origemSessao(sessao);
            if (incluirReservas && unidade.equals(sessao.getUnidadeOrigemChave())) {
                if (origem == AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) sessoes.add(p);
                if (sessao.getStatus() != null && sessao.getStatus() != StatusSessaoGrupo.REALIZADA
                        && p.getStatusPresenca() == StatusPresencaGrupo.NAO_REGISTRADA) continue;
            }
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
        var diagnostico = new Diagnostico(List.copyOf(eventos), agendamentosSemOrigem, gruposSemOrigem,
                inconsistentes, agendamentosLocaisPendentes);
        return new Evidencias(diagnostico, List.copyOf(agenda), List.copyOf(sessoes), agendaLocalInconclusiva);
    }
}

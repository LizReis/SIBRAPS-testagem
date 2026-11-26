package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;

import lombok.RequiredArgsConstructor;

@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
class HistoricoFrequenciaLocalEnsaio {
    private final HistoricoPacienteRepository historicos;
    private final LinhaDoTempoFrequenciasVinculoService linha;
    private final jakarta.persistence.EntityManager em;
    private final OrigemHistoricaEfetivaLocal origemEfetiva;

    /**
     * Recalcula somente a numeração derivada, sem criar fatos ou corrigir a
     * origem. Históricos ausentes, divergentes ou duplicados não são escritos.
     * Empate entre faltas impede atribuição do número a cada fato.
     */
    void renumerar(VinculoAssistencial local) {
        var evidencias=linha.examinarEfetivo(local,true);
        // A numeração é derivada: retirar somente do fato desconsiderado comprovado,
        // inclusive quando a ordem das demais faltas não pode ser atribuída.
        for(var evento:evidencias.diagnosticoBruto().eventosComOrigemLocal()) {
            if(!evidencias.desconsiderado(evento.fonte(),evento.registroId())) continue;
            var registro=historicoCoerente(local,evento);
            if(registro!=null && registro.getNumeroFaltaConsecutiva()!=null)
                registro.setNumeroFaltaConsecutiva(null);
        }
        var diagnostico = evidencias.diagnostico();
        if (!DiagnosticoFaltasConsecutivasVinculoService.calcular(diagnostico).podeAvaliarSequencia()) return;
        var eventos = diagnostico.eventosComOrigemLocal();
        for (int i = 1; i < eventos.size(); i++) {
            var anterior = eventos.get(i - 1);
            var atual = eventos.get(i);
            if (!anterior.presente() && !atual.presente() && anterior.ocorridoEm().equals(atual.ocorridoEm())) return;
        }
        int faltas = 0;
        for (var evento : eventos) {
            faltas = evento.presente() ? 0 : faltas + 1;
            HistoricoPaciente h=historicoCoerente(local,evento);
            if(h==null) continue;
            Integer numero = evento.presente() ? null : faltas;
            if (!Objects.equals(h.getNumeroFaltaConsecutiva(), numero)) h.setNumeroFaltaConsecutiva(numero);
        }
    }

    private HistoricoPaciente historicoCoerente(VinculoAssistencial local,LinhaDoTempoFrequenciasVinculoService.Evento evento) {
        var frequencia=evento.presente()?FrequenciaAssistencial.PRESENTE:FrequenciaAssistencial.FALTOU;
        try {
            var h=evento.fonte()==LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL
                    ?individual(em.find(Agendamento.class,evento.registroId()),local,frequencia)
                    :coletivo(em.find(SessaoGrupoParticipante.class,evento.registroId()),local,frequencia);
            return h!=null && evento.ocorridoEm().equals(h.getOcorridoEm())?h:null;
        } catch(ConflictException inconclusivo) {return null;}
    }

    void exigirSemResolucaoAtiva(VinculoAssistencial local,LinhaDoTempoFrequenciasVinculoService.Fonte fonte,Long id) {
        linha.exigirSemResolucaoAtiva(local,fonte,id);
    }

    HistoricoPaciente individual(Agendamento a, VinculoAssistencial local, FrequenciaAssistencial antes) {
        var eventos = historicos.findByAgendamento(a).stream().filter(h -> h.getTipo() == TipoEventoHistoricoPaciente.FALTA
                || h.getTipo() == TipoEventoHistoricoPaciente.PRESENCA
                || h.getTipo() == TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO).toList();
        return conferir(eventos, local, a.getUsuario(), antes, null);
    }

    HistoricoPaciente coletivo(SessaoGrupoParticipante p, VinculoAssistencial local, FrequenciaAssistencial antes) {
        var sessao = p.getSessaoGrupo();
        return conferir(historicos.findAllByPacienteAndSessaoGrupo(p.getPaciente(), sessao),
                local, sessao.getResponsavelAtendimento(), antes, sessao.getId());
    }

    private HistoricoPaciente conferir(List<HistoricoPaciente> eventos, VinculoAssistencial local,
            Usuario responsavel, FrequenciaAssistencial antes, Long sessao) {
        if (antes == FrequenciaAssistencial.NAO_REGISTRADA && eventos.isEmpty()) return null;
        if (antes != FrequenciaAssistencial.NAO_REGISTRADA && eventos.size() == 1) {
            var h = eventos.getFirst();
            boolean tipoCorreto = antes == FrequenciaAssistencial.FALTOU
                    ? h.getTipo() == TipoEventoHistoricoPaciente.FALTA
                    : h.getTipo() == TipoEventoHistoricoPaciente.PRESENCA
                        || h.getTipo() == TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO;
            if (tipoCorreto && Objects.equals(h.getPaciente().getId(), local.getPaciente().getId())
                    && (local.getUnidadeChave().equals(h.getUnidadeOrigemChave())
                        || (AvaliacaoProvenienciaClinica.origemAusente(h.getUnidadeOrigemChave())
                            && origemEfetiva.reconhecida(local,h.getId())))
                    && Objects.equals(sessao, h.getSessaoGrupo() == null ? null : h.getSessaoGrupo().getId())
                    && (sessao == null || h.getAgendamento() == null)
                    && h.getProfissional() != null && responsavel.getId().equals(h.getProfissional().getId())
                    && h.getSituacaoAtendimento() == SituacaoAtendimento.valueOf(antes.name())) return h;
        }
        throw new ConflictException("Histórico da frequência pendente de conferência.");
    }

    void registrarIndividual(Agendamento a, HistoricoPaciente existente, String descricao) {
        var h = existente == null ? new HistoricoPaciente() : existente;
        if (existente == null) {
            h.setPaciente(a.getPaciente());
            h.setAgendamento(a);
            h.setProfissional(a.getUsuario());
            h.registrarOrigemDoAgendamento(a);
            h.setOcorridoEm(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()));
        }
        registrar(h, a.getFrequenciaAssistencial(), false, descricao);
    }

    void registrarGrupo(SessaoGrupoParticipante p, HistoricoPaciente existente, String descricao) {
        var h = existente == null ? new HistoricoPaciente() : existente;
        if (existente == null) {
            var sessao = p.getSessaoGrupo();
            h.setPaciente(p.getPaciente());
            h.setSessaoGrupo(sessao);
            h.setProfissional(sessao.getResponsavelAtendimento());
            h.registrarOrigemDaSessao(sessao);
            h.setOcorridoEm(LocalDateTime.of(sessao.getDataSessao(), sessao.getHorario()));
        }
        registrar(h, FrequenciaAssistencial.valueOf(p.getStatusPresenca().name()), true, descricao);
    }

    private void registrar(HistoricoPaciente h, FrequenciaAssistencial frequencia, boolean grupo, String descricao) {
        boolean falta = frequencia == FrequenciaAssistencial.FALTOU;
        h.setTipo(falta ? TipoEventoHistoricoPaciente.FALTA : grupo
                ? TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO : TipoEventoHistoricoPaciente.PRESENCA);
        h.setSituacaoAtendimento(SituacaoAtendimento.valueOf(frequencia.name()));
        // Uma correção retroativa não usa o contador final como se fosse o do instante histórico.
        h.setNumeroFaltaConsecutiva(null);
        h.setDescricao(descricao);
        historicos.save(h);
    }
}

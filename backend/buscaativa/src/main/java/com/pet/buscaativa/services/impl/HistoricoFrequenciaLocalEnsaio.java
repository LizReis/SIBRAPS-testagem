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
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
class HistoricoFrequenciaLocalEnsaio {
    private final HistoricoPacienteRepository historicos;

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
                    && local.getUnidadeChave().equals(h.getUnidadeOrigemChave())
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

package com.pet.buscaativa.services.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;

class HistoricoFrequenciaLocalCoerenciaTests {
    private final HistoricoPacienteRepository repo = mock(HistoricoPacienteRepository.class);
    private final HistoricoFrequenciaLocalEnsaio service = new HistoricoFrequenciaLocalEnsaio(repo,
            mock(LinhaDoTempoFrequenciasVinculoService.class), mock(jakarta.persistence.EntityManager.class),
            mock(OrigemHistoricaEfetivaLocal.class));
    private final LocalDateTime horario = LocalDateTime.of(2026, 1, 10, 9, 0);
    private final Paciente paciente = mock(Paciente.class);
    private final Usuario profissional = mock(Usuario.class);
    private final VinculoAssistencial local = mock(VinculoAssistencial.class);

    private HistoricoPaciente historico(SessaoGrupo sessao) {
        when(paciente.getId()).thenReturn(10L);
        when(profissional.getId()).thenReturn(11L);
        when(local.getPaciente()).thenReturn(paciente);
        when(local.getUnidadeChave()).thenReturn("CAPS_AD");
        HistoricoPaciente h = mock(HistoricoPaciente.class);
        when(h.getPaciente()).thenReturn(paciente);
        when(h.getProfissional()).thenReturn(profissional);
        when(h.getUnidadeOrigemChave()).thenReturn("CAPS_AD");
        when(h.getTipo()).thenReturn(TipoEventoHistoricoPaciente.FALTA);
        when(h.getSituacaoAtendimento()).thenReturn(SituacaoAtendimento.FALTOU);
        when(h.getSessaoGrupo()).thenReturn(sessao);
        when(h.getOcorridoEm()).thenReturn(horario.plusHours(1));
        return h;
    }

    @Test
    void correcaoIndividualRecusaHorarioDivergenteEAceitaHistoricoCoerente() {
        Agendamento a = mock(Agendamento.class);
        when(a.getUsuario()).thenReturn(profissional);
        when(a.getDataAgendamento()).thenReturn(horario.toLocalDate());
        when(a.getHoraAtendimento()).thenReturn(horario.toLocalTime());
        var h = historico(null);
        when(repo.findByAgendamento(a)).thenReturn(List.of(h));
        assertThrows(ConflictException.class, () -> service.individual(a, local, FrequenciaAssistencial.FALTOU));
        when(h.getOcorridoEm()).thenReturn(horario);
        assertSame(h, service.individual(a, local, FrequenciaAssistencial.FALTOU));
        verify(repo, never()).save(any());
    }

    @Test
    void correcaoColetivaRecusaHorarioDivergenteEAceitaHistoricoCoerente() {
        SessaoGrupo sessao = mock(SessaoGrupo.class);
        when(sessao.getId()).thenReturn(12L);
        when(sessao.getResponsavelAtendimento()).thenReturn(profissional);
        when(sessao.getDataSessao()).thenReturn(horario.toLocalDate());
        when(sessao.getHorario()).thenReturn(horario.toLocalTime());
        SessaoGrupoParticipante p = mock(SessaoGrupoParticipante.class);
        when(p.getSessaoGrupo()).thenReturn(sessao);
        when(p.getPaciente()).thenReturn(paciente);
        var h = historico(sessao);
        when(repo.findAllByPacienteAndSessaoGrupo(paciente, sessao)).thenReturn(List.of(h));
        assertThrows(ConflictException.class, () -> service.coletivo(p, local, FrequenciaAssistencial.FALTOU));
        when(h.getOcorridoEm()).thenReturn(horario);
        assertSame(h, service.coletivo(p, local, FrequenciaAssistencial.FALTOU));
        verify(repo, never()).save(any());
    }
}

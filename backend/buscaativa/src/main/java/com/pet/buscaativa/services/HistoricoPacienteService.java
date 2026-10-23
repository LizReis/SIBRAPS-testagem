package com.pet.buscaativa.services;

import java.util.UUID;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.dto.HistoricoPacienteDTO;
import com.pet.buscaativa.entities.dto.RegistroHistoricoPacienteDTO;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;

public interface HistoricoPacienteService {

    HistoricoPacienteDTO consultar(UUID pacienteId);

    void registrarManual(UUID pacienteId, RegistroHistoricoPacienteDTO registro);

    void registrarAgendamento(Agendamento agendamento);

    void registrarAlteracaoDeAtendimento(Agendamento agendamento, SituacaoAtendimento statusAnterior);

    void registrarSituacaoAtual(Paciente paciente, String descricao);

    void registrarFrequenciaGrupo(Paciente paciente, SessaoGrupo sessao, StatusPresencaGrupo status);

    void corrigirFrequenciaGrupo(Paciente paciente, SessaoGrupo sessao, StatusPresencaGrupo status);
}
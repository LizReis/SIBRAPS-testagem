package com.pet.buscaativa.services;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.AlertaBuscaAtivaDTO;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.dto.ReativacaoPacienteDTO;
import com.pet.buscaativa.entities.dto.PacienteListaResponseDTO;
import com.pet.buscaativa.entities.dto.PacienteDetalheDTO;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;

@Service
public interface PacienteService {

    PacienteDTO save(PacienteDTO pacienteDTO, boolean ignorarSimilaridade);

    List<PacienteDTO> findAll();

    PacienteDTO findById(UUID idPublico);

    PacienteDetalheDTO findDetalhe(UUID idPublico);

    PacienteListaResponseDTO pesquisar(String q, ClassificacaoRisco classificacao, StatusPaciente status,
            TipoAcompanhamento tipoAcompanhamento, int page, int size);
    List<AgendamentoDTO> listarAgendamentos(UUID idPublico);

    PacienteDTO findByCns(String cns);

    PacienteDTO findByCpf(String cpf);

    List<PacienteDTO> findByNome(String nome);

    PacienteDTO findByNomeMae(String nomeMae);

    void inativarPaciente(UUID idPublico);

    void encerrarAcompanhamento(UUID idPublico, EncerramentoPacienteDTO encerramento);

    void reativarAcompanhamento(UUID idPublico, ReativacaoPacienteDTO reativacao);

    List<AlertaBuscaAtivaDTO> listarPacientesEmBuscaAtiva();
    
    void validarPacienteDuplicado(PacienteDTO pacienteDTO, boolean ignorarSimilaridade);
    
    void atualizarAssiduidadePaciente(Paciente paciente, SituacaoAtendimento statusAnterior,
            SituacaoAtendimento novoStatus, LocalDate dataAtendimento);

    void calcularEAtualizarRisco(Paciente paciente);

    void recalcularAssiduidadePaciente(Paciente paciente);

}
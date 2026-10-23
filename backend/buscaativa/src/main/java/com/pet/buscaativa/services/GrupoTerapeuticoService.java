package com.pet.buscaativa.services;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.dto.AdicionarParticipanteDTO;
import com.pet.buscaativa.entities.dto.AtualizarGrupoTerapeuticoDTO;
import com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO;
import com.pet.buscaativa.entities.dto.CorrigirFrequenciasGrupoDTO;
import com.pet.buscaativa.entities.dto.CriarGrupoDTO;
import com.pet.buscaativa.entities.dto.GrupoTerapeuticoDTO;
import com.pet.buscaativa.entities.dto.InscricaoFuturaGrupoDTO;
import com.pet.buscaativa.entities.dto.InscricaoRetroativaGrupoDTO;
import com.pet.buscaativa.entities.dto.NovaSessaoDTO;
import com.pet.buscaativa.entities.dto.ParticipanteGrupoDTO;
import com.pet.buscaativa.entities.dto.RegistrarPresencaGrupoDTO;
import com.pet.buscaativa.entities.dto.SessaoGrupoDTO;
import com.pet.buscaativa.entities.dto.SessaoInscricaoRetroativaDTO;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;

public interface GrupoTerapeuticoService {

    GrupoTerapeuticoDTO criarGrupo(CriarGrupoDTO dto);

    List<GrupoTerapeuticoDTO> listarGrupos();
    GrupoTerapeuticoDTO buscarGrupo(Long grupoId);
    GrupoTerapeuticoDTO atualizarGrupo(Long grupoId, AtualizarGrupoTerapeuticoDTO dto);

    LocalDate sugerirProximaData(Long grupoId);

    SessaoGrupoDTO criarProximaSessao(NovaSessaoDTO dto);

    List<SessaoGrupoDTO> listarSessoes(LocalDate dataInicio, LocalDate dataFim);

    SessaoGrupoDTO adicionarParticipante(Long sessaoId, AdicionarParticipanteDTO dto);

    SessaoGrupoDTO removerParticipante(Long sessaoId, UUID pacienteId);

    SessaoGrupoDTO atualizarStatus(Long sessaoId, StatusSessaoGrupo novoStatus, Integer expectedVersion);

    SessaoGrupoDTO registrarPresenca(Long sessaoId, UUID pacienteId, RegistrarPresencaGrupoDTO dto);

    SessaoGrupoDTO confirmarOcorrencia(Long sessaoId, ConfirmarOcorrenciaGrupoDTO dto);

    SessaoGrupoDTO inscreverRetroativamente(Long grupoId, InscricaoRetroativaGrupoDTO dto);

    List<SessaoInscricaoRetroativaDTO> listarSessoesParaInscricaoRetroativa(Long grupoId);

    SessaoGrupoDTO inscreverEmSessoesFuturas(Long grupoId, InscricaoFuturaGrupoDTO dto);

    void removerParticipanteDoGrupo(Long grupoId, UUID pacienteId);

    List<ParticipanteGrupoDTO> listarParticipantesDoGrupo(Long grupoId);

    SessaoGrupoDTO corrigirFrequencias(Long sessaoId, CorrigirFrequenciasGrupoDTO dto);
}
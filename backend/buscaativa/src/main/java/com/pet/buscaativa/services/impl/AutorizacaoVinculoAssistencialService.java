package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoParticipanteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Autorizacao INTERNA e estrita para o futuro corte ao escopo por vinculo.
 *
 * Nao e usada pelas rotas clinicas atuais: aplica-la parcialmente sem migrar
 * historico, indicadores, listas e grupos criaria falhas e acessos incoerentes.
 *
 * A identidade global e a referencia territorial nao concedem acesso clinico.
 * Registros de origem antiga nao comprovada NAO sao resolvidos pela lotacao
 * atual do usuario nem pelo CAPS/USF de referencia.
 */
@Service
@RequiredArgsConstructor
public class AutorizacaoVinculoAssistencialService {
    private final EscopoUnidadeService escopo;
    private final PacienteRepository pacientes;
    private final VinculoAssistencialRepository vinculos;
    private final SessaoGrupoParticipanteRepository participantes;

    /**
     * Exige vinculo explicito com a propria unidade autenticada.
     * Nao devolve dados de outros vinculos da mesma identidade.
     */
    @Transactional(readOnly = true)
    public VinculoAssistencial exigirVinculoLocal(UUID pacienteIdPublico) {
        escopo.validarUnidadeConfigurada();
        Usuario ator = escopo.usuarioLogado();
        if (pacienteIdPublico == null) throw pacienteNaoEncontrado();

        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(ator);
        return pacientes.findByIdPublico(pacienteIdPublico)
                .flatMap(p -> vinculos.findByPacienteIdAndUnidadeChave(p.getId(), unidade)
                        .filter(v -> v.getPaciente() != null
                                && Objects.equals(v.getPaciente().getId(), p.getId())
                                && unidade.equals(v.getUnidadeChave())
                                && v.getId() != null))
                .orElseThrow(AutorizacaoVinculoAssistencialService::pacienteNaoEncontrado);
    }

    /**
     * Apenas para o futuro caminho de leitura/escrita clinica por vinculo.
     * Sem proveniencia imutavel ou sem vinculo local, negar acesso.
     */
    @Transactional(readOnly = true)
    public void validarAgendamentoLocal(Agendamento agendamento) {
        if (agendamento == null || agendamento.getPaciente() == null
                || agendamento.getUnidadeOrigemChave() == null)
            throw new ResourceNotFoundException("Agendamento não encontrado.");
        VinculoAssistencial vinculo = exigirVinculoLocal(agendamento.getPaciente().getIdPublico());
        String unidadeLocal = vinculo.getUnidadeChave();
        Long pacienteId = agendamento.getPaciente().getId();
        if (AvaliacaoProvenienciaClinica.cadeiaAgendamento(agendamento, pacienteId, unidadeLocal)
                != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) {
            throw new ResourceNotFoundException("Agendamento não encontrado.");
        }
    }

    /** A origem da sessão prevalece sobre a lotação ATUAL do coordenador. */
    @Transactional(readOnly = true)
    public void validarSessaoLocal(SessaoGrupo sessao, Paciente paciente) {
        if (sessao == null || paciente == null || sessao.getUnidadeOrigemChave() == null)
            throw new ResourceNotFoundException("Sessão não encontrada.");
        VinculoAssistencial vinculo = exigirVinculoLocal(paciente.getIdPublico());
        if (!Objects.equals(vinculo.getUnidadeChave(), sessao.getUnidadeOrigemChave())
                || sessao.getGrupo() == null
                || !Objects.equals(vinculo.getUnidadeChave(), sessao.getGrupo().getUnidadeOrigemChave())
                || participantes.findBySessaoGrupoAndPaciente(sessao, paciente).isEmpty())
            throw new ResourceNotFoundException("Sessão não encontrada.");
    }

    /**
     * Antes de liberar historico, exigir origem propria e coerencia com a fonte.
     * Eventos antigos sem origem nao sao mostrados por esta nova via.
     */
    @Transactional(readOnly = true)
    public void validarHistoricoLocal(HistoricoPaciente historico) {
        if (historico == null || historico.getPaciente() == null
                || historico.getUnidadeOrigemChave() == null)
            throw new ResourceNotFoundException("Histórico não encontrado.");
        VinculoAssistencial vinculo = exigirVinculoLocal(historico.getPaciente().getIdPublico());
        String origem = historico.getUnidadeOrigemChave();
        if (!Objects.equals(vinculo.getUnidadeChave(), origem))
            throw new ResourceNotFoundException("Histórico não encontrado.");
        if (historico.getAgendamento() != null) {
            var fonte = historico.getAgendamento();
            if (historico.getSessaoGrupo() != null
                    || fonte.getPaciente() == null
                    || !Objects.equals(historico.getPaciente().getId(), fonte.getPaciente().getId())
                    || !Objects.equals(origem, fonte.getUnidadeOrigemChave()))
                throw new ResourceNotFoundException("Histórico não encontrado.");
            // A cadeia da fonte tambem deve ser integralmente local:
            // historico novo nao pode "lavar" uma remarcacao externa.
            validarAgendamentoLocal(fonte);
        }
        if (historico.getSessaoGrupo() != null) {
            var sessao = historico.getSessaoGrupo();
            if (sessao.getGrupo() == null
                    || !Objects.equals(origem, sessao.getUnidadeOrigemChave())
                    || !Objects.equals(origem, sessao.getGrupo().getUnidadeOrigemChave())
                    || participantes.findBySessaoGrupoAndPaciente(sessao, historico.getPaciente()).isEmpty())
                throw new ResourceNotFoundException("Histórico não encontrado.");
        }
    }

    private static ResourceNotFoundException pacienteNaoEncontrado() {
        return new ResourceNotFoundException("Paciente não encontrado.");
    }
}

package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Pre-condicao de leitura para FUTURA abertura de pendencia de proveniencia.
 *
 * Consulta o fato real no servidor; nunca aceita origem, paciente, unidade ou
 * evidencia clinica trazidos pelo cliente como comprovacao. A verificacao
 * positiva NAO concede permissao de alterar a proveniencia nem registra ticket.
 *
 * Fatos legados com origem NULL ficam fora deste caminho ate existir
 * reconciliacao humana com evidencia segura; nenhuma lotacao atual ou
 * referencia territorial pode preencher essa lacuna automaticamente.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificacaoFatoRevisaoProvenienciaService {
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final AgendamentoRepository agendamentos;
    private final HistoricoPacienteRepository historicos;
    private final SessaoGrupoRepository sessoes;

    /** Apenas o minimo necessario para uma futura etapa INTERNA de solicitacao. */
    public record FatoLocalVerificado(
            TipoRegistro tipoRegistro, Long registroId, Long pacienteId, String unidadeChave) {}

    public FatoLocalVerificado verificar(UUID pacienteIdPublico,
            TipoRegistro tipoRegistro, Long registroId) {
        var usuario = escopo.usuarioLogado();
        TipoUsuario perfil = usuario == null ? null : usuario.getTipoUsuario();
        if (perfil != TipoUsuario.ADMINISTRADOR && perfil != TipoUsuario.PROFISSIONAL)
            throw new AccessDeniedException("Perfil sem permissao para revisar proveniencia.");

        if (pacienteIdPublico == null || tipoRegistro == null
                || registroId == null || registroId <= 0)
            throw naoEncontrado();

        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteIdPublico);
        Paciente paciente = vinculo == null ? null : vinculo.getPaciente();
        if (paciente == null || paciente.getId() == null
                || !Objects.equals(pacienteIdPublico, paciente.getIdPublico())
                || vinculo.getUnidadeChave() == null || vinculo.getUnidadeChave().isBlank())
            throw naoEncontrado();

        String unidade = vinculo.getUnidadeChave();
        switch (tipoRegistro) {
            case AGENDAMENTO -> {
                Agendamento fato = agendamentos.findById(registroId).orElseThrow(
                        VerificacaoFatoRevisaoProvenienciaService::naoEncontrado);
                if (fato.getPaciente() == null
                        || !Objects.equals(paciente.getId(), fato.getPaciente().getId())
                        || !Objects.equals(unidade, fato.getUnidadeOrigemChave()))
                    throw naoEncontrado();
                autorizacao.validarAgendamentoLocal(fato);
            }
            case HISTORICO -> {
                HistoricoPaciente fato = historicos.findById(registroId).orElseThrow(
                        VerificacaoFatoRevisaoProvenienciaService::naoEncontrado);
                if (fato.getPaciente() == null
                        || !Objects.equals(paciente.getId(), fato.getPaciente().getId())
                        || !Objects.equals(unidade, fato.getUnidadeOrigemChave()))
                    throw naoEncontrado();
                autorizacao.validarHistoricoLocal(fato);
                var sessao = fato.getSessaoGrupo();
                if (sessao != null && (sessao.getStatus() != StatusSessaoGrupo.REALIZADA
                        || AvaliacaoProvenienciaClinica.origemSessao(sessao)
                            != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA)) {
                    throw naoEncontrado();
                }
                if (sessao == null && fato.getAgendamento() == null
                        && !AvaliacaoProvenienciaClinica.acaoLocalSemFonteRegistrada(fato)) {
                    throw naoEncontrado();
                }
            }
            case SESSAO_GRUPO -> {
                SessaoGrupo fato = sessoes.findById(registroId).orElseThrow(
                        VerificacaoFatoRevisaoProvenienciaService::naoEncontrado);
                if (!Objects.equals(unidade, fato.getUnidadeOrigemChave())
                        || fato.getGrupo() == null
                        || !Objects.equals(unidade, fato.getGrupo().getUnidadeOrigemChave()))
                    throw naoEncontrado();
                // O vinculo do paciente sozinho nao autoriza leitura da sessao:
                // o autorizador tambem exige participacao comprovada no grupo.
                autorizacao.validarSessaoLocal(fato, paciente);
                if (AvaliacaoProvenienciaClinica.origemSessao(fato)
                        != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) {
                    throw naoEncontrado();
                }
            }
        }
        return new FatoLocalVerificado(tipoRegistro, registroId, paciente.getId(), unidade);
    }

    private static ResourceNotFoundException naoEncontrado() {
        return new ResourceNotFoundException("Registro nao encontrado.");
    }
}

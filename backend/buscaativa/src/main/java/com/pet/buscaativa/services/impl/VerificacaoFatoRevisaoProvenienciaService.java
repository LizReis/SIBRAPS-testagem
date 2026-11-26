package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.List;
import java.time.LocalDateTime;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
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
            TipoRegistro tipoRegistro, Long registroId, Long pacienteId, String unidadeChave,
            boolean historicoSemOrigemComFonteLocal, ProvaOrigem provaOrigem) {
        public FatoLocalVerificado(TipoRegistro tipoRegistro,Long registroId,Long pacienteId,String unidadeChave) {
            this(tipoRegistro,registroId,pacienteId,unidadeChave,false,null);
        }
    }

    public record CandidatoOrigem(Long historico,TipoRegistro fonte,Long registroFonte,LocalDateTime ocorridoEm,
            FrequenciaAssistencial frequencia) {}
    public record CandidatosOrigem(Integer versaoVinculo,List<CandidatoOrigem> candidatos) {}
    /** Somente uso interno, não faz parte do DTO de consulta HTTP. */
    public record ProvaOrigem(CandidatoOrigem candidato,Integer versaoFonte,Long participanteId,Long responsavelId,
            TipoEventoHistoricoPaciente tipoHistorico) {}

    /** Somente análise administrativa: não libera o prontuário nem grava origem. */
    public CandidatosOrigem candidatosOrigem(UUID pacienteId,UUID vinculoId) {
        var ator=escopo.usuarioLogado();
        if(ator==null || ator.getTipoUsuario()!=TipoUsuario.ADMINISTRADOR)
            throw new AccessDeniedException("Perfil sem permissão para revisar origem histórica.");
        var vinculo=autorizacao.exigirVinculoIdentificado(pacienteId,vinculoId);
        var candidatos=historicos.findCandidatosOrigemLocal(pacienteId,vinculo.getUnidadeChave()).stream()
                .flatMap(h->{
                    try {return java.util.stream.Stream.of(conferirHistoricoSemOrigem(h,vinculo).candidato());}
                    catch(ResourceNotFoundException e) {return java.util.stream.Stream.empty();}
                }).toList();
        return new CandidatosOrigem(vinculo.getVersion(),candidatos);
    }

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
                if(fato.getPaciente()==null || !Objects.equals(paciente.getId(),fato.getPaciente().getId()))
                    throw naoEncontrado();
                if(AvaliacaoProvenienciaClinica.origemAusente(fato.getUnidadeOrigemChave())) {
                    if(perfil!=TipoUsuario.ADMINISTRADOR) throw naoEncontrado();
                    var prova=conferirHistoricoSemOrigem(fato,vinculo);
                    return new FatoLocalVerificado(tipoRegistro,registroId,paciente.getId(),unidade,true,prova);
                }
                if(!Objects.equals(unidade,fato.getUnidadeOrigemChave())) throw naoEncontrado();
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
                if (fato.isResponsabilidadePendente() || AvaliacaoProvenienciaClinica.origemSessao(fato)
                        != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) {
                    throw naoEncontrado();
                }
            }
        }
        return new FatoLocalVerificado(tipoRegistro, registroId, paciente.getId(), unidade);
    }

    ProvaOrigem conferirHistoricoSemOrigem(HistoricoPaciente h,VinculoAssistencial local) {
        if(!AvaliacaoProvenienciaClinica.origemAusente(h.getUnidadeOrigemChave())
                || h.getPaciente()==null || !Objects.equals(h.getPaciente().getId(),local.getPaciente().getId())
                || h.getProfissional()==null || h.getProfissional().getId()==null || h.getOcorridoEm()==null
                || !eventoFrequencia(h) || (h.getAgendamento()==null)==(h.getSessaoGrupo()==null))
            throw naoEncontrado();
        String unidade=local.getUnidadeChave();
        FrequenciaAssistencial frequencia;
        LocalDateTime horario;
        Long responsavel,fonteId,participanteId=null;
        Integer versaoFonte;
        TipoRegistro fonte;
        List<HistoricoPaciente> associados;
        if(h.getAgendamento()!=null) {
            var a=h.getAgendamento();
            if(a.getId()==null || a.getPaciente()==null || !Objects.equals(a.getPaciente().getId(),local.getPaciente().getId())
                    || !unidade.equals(a.getUnidadeOrigemChave()) || a.getUsuario()==null
                    || !AvaliacaoProvenienciaClinica.frequenciaIndividualRegistrada(a)) throw naoEncontrado();
            autorizacao.validarAgendamentoLocal(a);
            frequencia=a.getFrequenciaAssistencial();
            horario=LocalDateTime.of(a.getDataAgendamento(),a.getHoraAtendimento());
            responsavel=a.getUsuario().getId();fonteId=a.getId();fonte=TipoRegistro.AGENDAMENTO;
            versaoFonte=a.getVersion();
            associados=historicos.findByAgendamento(a).stream().filter(VerificacaoFatoRevisaoProvenienciaService::eventoFrequencia).toList();
        } else {
            var s=h.getSessaoGrupo();
            if(s.getId()==null || !unidade.equals(s.getUnidadeOrigemChave())
                    || s.getStatus()!=StatusSessaoGrupo.REALIZADA
                    || AvaliacaoProvenienciaClinica.origemSessao(s)!=AvaliacaoProvenienciaClinica.Situacao.COMPROVADA)
                throw naoEncontrado();
            autorizacao.validarSessaoLocal(s,local.getPaciente());
            var ps=s.getParticipantes().stream().filter(p->p.getPaciente()!=null
                    && Objects.equals(p.getPaciente().getId(),local.getPaciente().getId())).toList();
            if(ps.size()!=1 || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(ps.getFirst().getStatusPresenca()))
                throw naoEncontrado();
            frequencia=FrequenciaAssistencial.valueOf(ps.getFirst().getStatusPresenca().name());
            horario=LocalDateTime.of(s.getDataSessao(),s.getHorario());
            responsavel=s.getResponsavelAtendimento().getId();fonteId=s.getId();fonte=TipoRegistro.SESSAO_GRUPO;
            versaoFonte=s.getVersion();participanteId=ps.getFirst().getId();
            if(participanteId==null || participanteId<=0) throw naoEncontrado();
            associados=historicos.findAllByPacienteAndSessaoGrupo(local.getPaciente(),s);
        }
        boolean tipoCorreto=frequencia==FrequenciaAssistencial.FALTOU?h.getTipo()==TipoEventoHistoricoPaciente.FALTA
                :h.getTipo()==TipoEventoHistoricoPaciente.PRESENCA || h.getTipo()==TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO;
        // Exigir a mesma coerência que o escritor de frequência local exige.
        // Divergência de autoria/responsabilidade fica para análise específica.
        if(!tipoCorreto || !horario.equals(h.getOcorridoEm()) || !Objects.equals(responsavel,h.getProfissional().getId())
                || h.getSituacaoAtendimento()!=SituacaoAtendimento.valueOf(frequencia.name())
                || associados.size()!=1 || h.getId()==null || !Objects.equals(h.getId(),associados.getFirst().getId()))
            throw naoEncontrado();
        if(versaoFonte==null || versaoFonte<0 || responsavel==null) throw naoEncontrado();
        return new ProvaOrigem(new CandidatoOrigem(h.getId(),fonte,fonteId,horario,frequencia),
                versaoFonte,participanteId,responsavel,h.getTipo());
    }

    private static boolean eventoFrequencia(HistoricoPaciente h) {
        return h.getTipo()==TipoEventoHistoricoPaciente.FALTA || h.getTipo()==TipoEventoHistoricoPaciente.PRESENCA
                || h.getTipo()==TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO;
    }

    private static ResourceNotFoundException naoEncontrado() {
        return new ResourceNotFoundException("Registro nao encontrado.");
    }
}

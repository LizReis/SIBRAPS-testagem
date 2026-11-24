package com.pet.buscaativa.services.impl;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ConflictException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Projeção experimental de DTOs existentes; nenhuma rota nova. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class DtosAssistenciaisLocaisEnsaioService {
    private final EntityManager em;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final GrupoTerapeuticoServiceImpl guardas;

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public AgendamentoDTO atendimento(Long id) {
        var contexto = atores.autorizarOperacional();
        var a = em.createQuery("select a from Agendamento a where a.id=:id and a.unidadeOrigemChave=:unidade",Agendamento.class)
                .setParameter("id",id).setParameter("unidade",contexto.unidade()).getResultList().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Atendimento não encontrado."));
        autorizacao.validarAgendamentoLocal(a);
        var v = autorizacao.exigirVinculoLocal(a.getPaciente().getIdPublico());
        if (!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw new ResourceNotFoundException("Atendimento não encontrado.");
        return new AgendamentoDTO(a.getId(),a.getUsuario().getIdPublico(),a.getUsuario().getNome(),
                a.getPaciente().getIdPublico(),a.getPaciente().getNome(),v.getTipoAcompanhamento(),
                a.getDataAgendamento(),a.getTurnoAgendamento(),a.getHoraAtendimento(),a.getSituacaoAtendimento(),
                a.getAgendamentoOriginal()==null?null:a.getAgendamentoOriginal().getId(),a.getVersion(),
                a.getSituacaoReserva(),a.getFrequenciaAssistencial(),a.isMigracaoPendente(),a.getMotivoMigracaoPendente(),a.getOcupaVaga());
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public SessaoGrupoDTO sessao(Long id) {
        var contexto = atores.autorizarOperacional();
        var s = em.createQuery("""
                select s from SessaoGrupo s where s.id=:id and s.unidadeOrigemChave=:unidade
                and s.grupo.unidadeOrigemChave=:unidade
                """,SessaoGrupo.class).setParameter("id",id).setParameter("unidade",contexto.unidade())
                .getResultList().stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        if (AvaliacaoProvenienciaClinica.origemSessao(s)!=AvaliacaoProvenienciaClinica.Situacao.COMPROVADA)
            throw new ConflictException("Sessão pendente de conferência.");
        // Falhar fechado em composição inconsistente, não mostrar lote parcial.
        List<ParticipanteSessaoDTO> ps = s.getParticipantes().stream().map(p -> {
            autorizacao.validarSessaoLocal(s,p.getPaciente());
            var v = autorizacao.exigirVinculoLocal(p.getPaciente().getIdPublico());
            if (!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw new ResourceNotFoundException("Sessão não encontrada.");
            return new ParticipanteSessaoDTO(p.getPaciente().getIdPublico(),p.getPaciente().getNome(),p.getStatusPresenca());
        }).toList();
        var status = switch (s.getStatus()) {
            case REALIZADA -> StatusExibicaoSessaoGrupo.REALIZADO;
            case CANCELADA -> StatusExibicaoSessaoGrupo.CANCELADO;
            case AGENDADA -> guardas.sessaoAindaNaoIniciou(s)?StatusExibicaoSessaoGrupo.AGENDADO:StatusExibicaoSessaoGrupo.EM_ANDAMENTO;
        };
        int presencas=(int)ps.stream().filter(p -> p.statusPresenca()==StatusPresencaGrupo.PRESENTE).count();
        return new SessaoGrupoDTO(s.getId(),s.getGrupo().getId(),s.getGrupo().getTema(),s.getGrupo().getCoordenador().getNome(),
                s.getDataSessao(),s.getHorario(),s.getStatus(),status,s.getMotivoCancelamento(),ps,ps.size(),presencas,s.getVersion(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getIdPublico(),
                s.getResponsavelAtendimento()==null?null:s.getResponsavelAtendimento().getNome(),
                s.isResponsabilidadePendente(),s.getMotivoResponsabilidadePendente(),s.getOrigemResponsabilidade());
    }
}

package com.pet.buscaativa.services;

import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import lombok.RequiredArgsConstructor;

/** Responsibility is checked in services, including retries and legacy adapters. */
@Service
@RequiredArgsConstructor
public class AutorizacaoClinicaService {
    private final EscopoUnidadeService scope;
    public void validarAgendamento(Agendamento appointment, boolean correction) {
        scope.validarAgendamento(appointment);
        var actor=scope.usuarioLogado();
        if (actor.getTipoUsuario()==TipoUsuario.ADMINISTRADOR) return;
        if (actor.getTipoUsuario()==TipoUsuario.PROFISSIONAL && Objects.equals(actor.getId(),appointment.getUsuario().getId())) return;
        // Reception's preexisting initial/administrative operation is not a clinical correction.
        if (actor.getTipoUsuario()==TipoUsuario.RECEPCAO && !correction) return;
        throw denied();
    }
    public void validarSessao(SessaoGrupo session) {
        scope.validarSessaoCompleta(session);
        if (session.getStatus()!=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.REALIZADA) {
            validarResponsavel(session.getGrupo().getCoordenador()); return;
        }
        if (session.isResponsabilidadePendente() || session.getResponsavelAtendimento()==null)
            throw new com.pet.buscaativa.services.exceptions.ConflictException("Responsabilidade histórica pendente de reconciliação; nenhuma frequência foi alterada.");
        validarIdentidadeHistorica(session);
    }
    public void validarConsultaAuditoriaSessao(SessaoGrupo session) {
        scope.validarSessaoCompleta(session);
        if (scope.usuarioLogado().getTipoUsuario()==TipoUsuario.ADMINISTRADOR) return;
        if (session.getStatus()!=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.REALIZADA) {
            validarResponsavel(session.getGrupo().getCoordenador()); return;
        }
        if (session.isResponsabilidadePendente() || session.getResponsavelAtendimento()==null) throw denied();
        validarIdentidadeHistorica(session);
    }
    private void validarIdentidadeHistorica(SessaoGrupo session) {
        var actor=scope.usuarioLogado();
        // Full resource scope was already checked. A moved former owner cannot act in the old unit;
        // the unit administrator can still supervise without changing that historical identity.
        if (actor.getTipoUsuario()==TipoUsuario.ADMINISTRADOR || actor.getTipoUsuario()==TipoUsuario.PROFISSIONAL
                && Objects.equals(actor.getId(),session.getResponsavelAtendimento().getId())) return;
        throw denied();
    }
    public void validarGrupoClinico(GrupoTerapeutico group) {
        scope.validarGrupo(group); validarResponsavel(group.getCoordenador());
    }
    public void validarEdicaoGrupo(GrupoTerapeutico group) {
        scope.validarGrupo(group);
        if (scope.usuarioLogado().getTipoUsuario()==TipoUsuario.RECEPCAO) return;
        validarResponsavel(group.getCoordenador());
    }
    public void validarResponsavel(Usuario responsible) {
        scope.validarUsuario(responsible);
        var actor=scope.usuarioLogado();
        if (actor.getTipoUsuario()==TipoUsuario.ADMINISTRADOR || actor.getTipoUsuario()==TipoUsuario.PROFISSIONAL
                && Objects.equals(actor.getId(),responsible.getId())) return;
        throw denied();
    }
    private AccessDeniedException denied() {return new AccessDeniedException("Usuário sem autorização para esta operação clínica.");}
}

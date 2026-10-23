package com.pet.buscaativa.services.impl;

import java.util.Objects;

import org.hibernate.envers.query.AuditEntity;
import org.hibernate.envers.query.criteria.AuditCriterion;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.UnidadePainelDTO;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.criteria.Path;
import lombok.RequiredArgsConstructor;

/** Escopo de dados independente das permissões funcionais de cada role. */
@Service
@RequiredArgsConstructor
public class EscopoUnidadeService {
    private final UsuarioRepository usuarioRepository;

    public Usuario usuarioLogado() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Usuário autenticado não identificado.");
        }
        // O filtro JWT existente recarrega este principal do banco a cada requisição.
        if (auth.getPrincipal() instanceof Usuario usuario) return usuario;
        return usuarioRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Usuário autenticado não identificado."));
    }

    private Usuario usuarioComUnidade() {
        Usuario usuario = usuarioLogado();
        if (usuario.getUnidadeAtuacao() == null) {
            throw new ValidationException("A unidade de atuação deste usuário ainda não foi configurada.");
        }
        if (usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                && (usuario.getUsfAtuacao() == null || usuario.getUsfAtuacao().getId() == null)) {
            throw new ValidationException("A unidade USF específica deste usuário ainda não foi configurada.");
        }
        return usuario;
    }

    public void validarUnidadeConfigurada() {
        usuarioComUnidade();
    }

    public <T> Specification<T> specificationPaciente(String... caminho) {
        Usuario usuario = usuarioComUnidade();
        return (root, query, cb) -> {
            Path<?> paciente = root;
            for (String parte : caminho) paciente = paciente.get(parte);
            return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                    ? cb.equal(paciente.get("usfReferencia").get("id"), usuario.getUsfAtuacao().getId())
                    : cb.equal(paciente.get("capsReferencia"), CapsEnum.valueOf(usuario.getUnidadeAtuacao().name()));
        };
    }

    public <T> Specification<T> specificationUsuario(String... caminho) {
        Usuario usuario = usuarioComUnidade();
        return (root, query, cb) -> {
            Path<?> alvo = root;
            for (String parte : caminho) alvo = alvo.get(parte);
            var unidade = cb.equal(alvo.get("unidadeAtuacao"), usuario.getUnidadeAtuacao());
            return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                    ? cb.and(unidade, cb.equal(alvo.get("usfAtuacao").get("id"), usuario.getUsfAtuacao().getId()))
                    : unidade;
        };
    }

    public AuditCriterion criterioPacienteAuditoria() {
        Usuario usuario = usuarioComUnidade();
        return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? AuditEntity.relatedId("usfReferencia").eq(usuario.getUsfAtuacao().getId())
                : AuditEntity.property("capsReferencia").eq(CapsEnum.valueOf(usuario.getUnidadeAtuacao().name()));
    }

    public boolean pacientePertenceAoEscopo(Paciente paciente) {
        Usuario usuario = usuarioComUnidade();
        if (paciente == null) return false;
        return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? paciente.getUsfReferencia() != null
                    && Objects.equals(paciente.getUsfReferencia().getId(), usuario.getUsfAtuacao().getId())
                : paciente.getCapsReferencia() == CapsEnum.valueOf(usuario.getUnidadeAtuacao().name());
    }

    public boolean usuarioPertenceAoEscopo(Usuario alvo) {
        Usuario usuario = usuarioComUnidade();
        if (alvo == null || alvo.getUnidadeAtuacao() != usuario.getUnidadeAtuacao()) return false;
        return usuario.getUnidadeAtuacao() != UnidadeAtuacao.USF || alvo.getUsfAtuacao() != null
                && Objects.equals(alvo.getUsfAtuacao().getId(), usuario.getUsfAtuacao().getId());
    }

    public void validarAcesso(Paciente paciente) {
        if (!pacientePertenceAoEscopo(paciente)) throw new ResourceNotFoundException("Paciente não encontrado.");
    }

    public void validarUsuario(Usuario usuario) {
        if (!usuarioPertenceAoEscopo(usuario)) throw new ResourceNotFoundException("Usuário não encontrado.");
    }

    public boolean agendamentoPertenceAoEscopo(Agendamento agendamento) {
        return pacientePertenceAoEscopo(agendamento.getPaciente()) && usuarioPertenceAoEscopo(agendamento.getUsuario());
    }

    public void validarAgendamento(Agendamento agendamento) {
        if (!agendamentoPertenceAoEscopo(agendamento)) throw new ResourceNotFoundException("Agendamento não encontrado.");
    }

    public boolean grupoPertenceAoEscopo(GrupoTerapeutico grupo) {
        return grupo != null && usuarioPertenceAoEscopo(grupo.getCoordenador());
    }

    public void validarGrupo(GrupoTerapeutico grupo) {
        if (!grupoPertenceAoEscopo(grupo)) throw new ResourceNotFoundException("Grupo não encontrado.");
    }

    /** Operações sobre a sessão inteira não podem alterar participantes de outra unidade. */
    public void validarSessaoCompleta(SessaoGrupo sessao) {
        validarGrupo(sessao.getGrupo());
        sessao.getParticipantes().forEach(p -> validarAcesso(p.getPaciente()));
    }

    public UnidadePainelDTO unidadePainel() {
        Usuario usuario = usuarioComUnidade();
        return usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? new UnidadePainelDTO("USF:" + usuario.getUsfAtuacao().getCnes(), usuario.getUsfAtuacao().getNomeUsf())
                : new UnidadePainelDTO("CAPS:" + usuario.getUnidadeAtuacao().name(), usuario.getUnidadeAtuacao().name().replace('_', ' '));
    }
}

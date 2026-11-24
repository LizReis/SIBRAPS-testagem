package com.pet.buscaativa.services.impl;

import java.util.TreeSet;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Component
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
class AtoresEstadoClinicoLocalEnsaio {
    private final EscopoUnidadeService escopo;
    private final UsuarioRepository usuarios;
    private final EntityManager em;

    record Contexto(UUID ator, String unidade, boolean somenteAdministrador) {}
    record Atores(Usuario ator, Usuario referencia) {}

    Contexto autorizar(boolean somenteAdministrador) {
        escopo.validarUnidadeConfigurada();
        var principal = escopo.usuarioLogado();
        if (principal.getId() == null || principal.getIdPublico() == null)
            throw new AccessDeniedException("Usuário não identificado.");
        var contexto = new Contexto(principal.getIdPublico(), ChaveUnidadeAssistencial.doUsuarioAtual(principal),
                somenteAdministrador);
        exigirAtor(contexto, usuarios.findById(principal.getId()).orElse(null));
        return contexto;
    }

    Atores bloquear(Contexto contexto, UUID referenciaId) {
        var ids = new TreeSet<UUID>();
        ids.add(contexto.ator());
        if (referenciaId != null) ids.add(referenciaId);
        Usuario ator = null;
        Usuario referencia = null;
        for (var id : ids) {
            var usuario = usuarios.findByIdPublicoForUpdate(id).orElse(null);
            if (usuario != null) em.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
            if (id.equals(contexto.ator())) ator = usuario;
            if (id.equals(referenciaId)) referencia = usuario;
        }
        exigirAtor(contexto, ator);
        if (referenciaId != null && !elegivel(referencia, contexto.unidade(), false)) {
            throw new ResourceNotFoundException("Profissional de referência não encontrado.");
        }
        return new Atores(ator, referencia);
    }

    private static void exigirAtor(Contexto contexto, Usuario ator) {
        if (ator == null || !contexto.ator().equals(ator.getIdPublico())
                || !elegivel(ator, contexto.unidade(), contexto.somenteAdministrador())) {
            throw new AccessDeniedException("Perfil ou unidade não autorizado.");
        }
    }

    private static boolean elegivel(Usuario usuario, String unidade, boolean somenteAdministrador) {
        if (usuario == null || (usuario.getTipoUsuario() != TipoUsuario.ADMINISTRADOR
                && (somenteAdministrador || usuario.getTipoUsuario() != TipoUsuario.PROFISSIONAL))) {
            return false;
        }
        try {
            return unidade.equals(ChaveUnidadeAssistencial.doUsuarioAtual(usuario));
        } catch (IllegalArgumentException | NullPointerException incoerente) {
            return false;
        }
    }
}

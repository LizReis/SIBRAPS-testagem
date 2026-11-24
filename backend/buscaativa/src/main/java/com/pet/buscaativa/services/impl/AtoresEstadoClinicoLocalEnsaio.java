package com.pet.buscaativa.services.impl;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

@Component
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
class AtoresEstadoClinicoLocalEnsaio {
    private final EscopoUnidadeService escopo;
    private final UsuarioRepository usuarios;

    record Contexto(UUID ator, String unidade, boolean somenteAdministrador, TipoUsuario perfil) {}
    record Atores(Usuario ator, Usuario referencia) {}

    Contexto autorizar(boolean somenteAdministrador) {
        return autorizar(somenteAdministrador, false);
    }

    /** Recepção pode operar reservas/inscrições, nunca frequência clínica. */
    Contexto autorizarOperacional() {
        return autorizar(false, true);
    }

    private Contexto autorizar(boolean somenteAdministrador, boolean permiteRecepcao) {
        escopo.validarUnidadeConfigurada();
        var principal = escopo.usuarioLogado();
        if (principal.getId() == null || principal.getIdPublico() == null)
            throw new AccessDeniedException("Usuário não identificado.");
        var atual = usuarios.findById(principal.getId())
                .orElseThrow(() -> new AccessDeniedException("Usuário não identificado."));
        if (!permiteRecepcao && atual.getTipoUsuario() == TipoUsuario.RECEPCAO)
            throw new AccessDeniedException("Perfil sem autorização clínica.");
        var contexto = new Contexto(principal.getIdPublico(), ChaveUnidadeAssistencial.doUsuarioAtual(principal),
                somenteAdministrador, atual.getTipoUsuario());
        exigirAtor(contexto, atual);
        return contexto;
    }

    Atores validar(Contexto contexto, UUID referenciaId, Map<UUID, Usuario> bloqueados) {
        var ator = bloqueados.get(contexto.ator());
        var referencia = referenciaId == null ? null : bloqueados.get(referenciaId);
        exigirAtor(contexto, ator);
        if (contexto.perfil() != ator.getTipoUsuario()) throw CoordenacaoEscritaClinicaEnsaio.mudou();
        if (referenciaId != null && !elegivel(referencia, contexto.unidade(), false)) {
            throw new ResourceNotFoundException("Profissional de referência não encontrado.");
        }
        return new Atores(ator, referencia);
    }

    private static void exigirAtor(Contexto contexto, Usuario ator) {
        boolean recepcaoOperacional = ator != null && contexto.perfil() == TipoUsuario.RECEPCAO
                && ator.getTipoUsuario() == TipoUsuario.RECEPCAO && !contexto.somenteAdministrador()
                && contexto.unidade().equals(ChaveUnidadeAssistencial.doUsuarioAtual(ator));
        if (ator == null || !contexto.ator().equals(ator.getIdPublico())
                || !(recepcaoOperacional || elegivel(ator, contexto.unidade(), contexto.somenteAdministrador()))) {
            throw new AccessDeniedException("Perfil ou unidade não autorizado.");
        }
    }

    static boolean elegivel(Usuario usuario, String unidade, boolean somenteAdministrador) {
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

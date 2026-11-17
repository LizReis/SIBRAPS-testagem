package com.pet.buscaativa.services.impl;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UsuarioContextService {

    private final UsuarioRepository usuarioRepository;
    private final EscopoUnidadeService escopoUnidadeService;
    private final jakarta.persistence.EntityManager entityManager;

    public Usuario determinarUsuarioAlvo(UUID usuarioIdPublico, String emailLogado) {
        if (emailLogado == null || emailLogado.isBlank()) {
            throw new ResourceNotFoundException("Usuário logado não identificado (email ausente).");
        }

        Usuario logado = usuarioRepository.findByEmail(emailLogado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário logado não encontrado: " + emailLogado));

        if (logado.getTipoUsuario() == TipoUsuario.ADMINISTRADOR
                || logado.getTipoUsuario() == TipoUsuario.RECEPCAO) {
            if (usuarioIdPublico == null) {
                throw new ValidationException("Selecione um profissional para configurar a agenda.");
            }
            Usuario alvo = usuarioRepository.findByIdPublico(usuarioIdPublico)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuário alvo não encontrado para id: " + usuarioIdPublico));
            if (alvo.getTipoUsuario() != TipoUsuario.PROFISSIONAL
                    && alvo.getTipoUsuario() != TipoUsuario.ADMINISTRADOR) {
                throw new ValidationException("O usuário informado não é um profissional.");
            }
            escopoUnidadeService.validarUsuario(alvo);
            return alvo;
        }

        if (usuarioIdPublico != null && !usuarioIdPublico.equals(logado.getIdPublico())) {
            throw new AccessDeniedException("Acesso a recurso de outro profissional.");
        }
        
        escopoUnidadeService.validarUsuario(logado);
        return logado;
    }

    /** Mutating agenda configuration shares the professional lock held by booking/recovery. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Usuario bloquearUsuarioAlvo(UUID usuarioIdPublico,String emailLogado) {
        var autorizado=determinarUsuarioAlvo(usuarioIdPublico,emailLogado);
        var bloqueado=usuarioRepository.findByIdPublicoForUpdate(autorizado.getIdPublico())
            .orElseThrow(()->new ResourceNotFoundException("Profissional não encontrado."));
        escopoUnidadeService.validarUsuario(bloqueado);
        return bloqueado;
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Usuario bloquearProprietario(Usuario proprietario,String emailLogado) {
        validarAlteracao(proprietario,emailLogado);
        return usuarioRepository.findByIdPublicoForUpdate(proprietario.getIdPublico())
            .orElseThrow(()->new ResourceNotFoundException("Profissional não encontrado."));
    }

    /** Bloqueia origem e destino em ordem estável, sem adquirir locks de pacientes. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Usuario bloquearConfiguracao(UUID destino, UUID origem, String emailLogado) {
        if (!java.util.Objects.equals(escopoUnidadeService.usuarioLogado().getEmail(), emailLogado))
            throw new AccessDeniedException("Identidade do executor incompatível com a autenticação.");
        Usuario alvo = determinarUsuarioAlvo(destino, emailLogado);
        var ids = new java.util.TreeSet<UUID>();
        ids.add(alvo.getIdPublico());
        if (origem != null) {
            var anterior = usuarioRepository.findByIdPublico(origem)
                    .orElseThrow(() -> new ResourceNotFoundException("Profissional não encontrado."));
            validarAlteracao(anterior, emailLogado);
            ids.add(origem);
        }
        Usuario resultado = null;
        for (UUID id : ids) {
            var bloqueado = usuarioRepository.findByIdPublicoForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Profissional não encontrado."));
            // findByIdPublico pode ter carregado o objeto no contexto antes da espera pelo lock.
            entityManager.refresh(bloqueado, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            validarAlteracao(bloqueado, emailLogado);
            if (id.equals(alvo.getIdPublico())) resultado = bloqueado;
        }
        return java.util.Objects.requireNonNull(resultado);
    }

    public void validarAlteracao(Usuario proprietario, String emailLogado) {
        escopoUnidadeService.validarUsuario(proprietario);
        Usuario logado = usuarioRepository.findByEmail(emailLogado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário autenticado não encontrado."));
        boolean gestorDeAgenda = (logado.getTipoUsuario() == TipoUsuario.ADMINISTRADOR
                || logado.getTipoUsuario() == TipoUsuario.RECEPCAO)
                && (proprietario.getTipoUsuario() == TipoUsuario.PROFISSIONAL
                || proprietario.getTipoUsuario() == TipoUsuario.ADMINISTRADOR);
        boolean profissionalProprietario = logado.getTipoUsuario() == TipoUsuario.PROFISSIONAL
                && logado.getId().equals(proprietario.getId());
        if (!gestorDeAgenda && !profissionalProprietario) {
            throw new AccessDeniedException("Você não pode alterar este recurso.");
        }
    }
}

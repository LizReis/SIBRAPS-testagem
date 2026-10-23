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

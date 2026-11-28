package com.pet.buscaativa.services;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Usuario;

@Service
public interface TokenService {
    
    String gerarToken(Usuario usuario);

    record Identidade(String email, UUID usuario) {}

    Optional<Identidade> verificarIdentidade(String token);

    default String validarToken(String token) {
        return verificarIdentidade(token).map(Identidade::email).orElse("");
    }
}

package com.pet.buscaativa.services;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.Usuario;

@Service
public interface TokenService {
    
    String gerarToken(Usuario usuario);

    String validarToken(String token);
}

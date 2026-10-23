package com.pet.buscaativa.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.dto.ProfissionalSelecaoDTO;
import com.pet.buscaativa.entities.dto.UsuarioDTO;
import com.pet.buscaativa.entities.dto.UsuarioReferenciaDTO;

@Service
public interface UsuarioService {

    UsuarioDTO save(UsuarioDTO usuarioDTO);

    void removerUsuario(UUID idPublico);

    public List<UsuarioDTO> findAll();

    List<ProfissionalSelecaoDTO> listarProfissionaisParaSelecao();

    List<UsuarioReferenciaDTO> listarUsuariosElegiveisParaReferencia();

    public UsuarioDTO findById(UUID idPublico);

    public UsuarioDTO findByEmail(String email);

    public void validarEmailDuplicado(String email, UUID idPublico);

}

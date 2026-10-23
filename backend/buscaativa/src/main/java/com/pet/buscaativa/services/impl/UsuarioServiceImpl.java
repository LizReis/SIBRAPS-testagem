package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.entities.dto.UsuarioDTO;
import com.pet.buscaativa.entities.dto.ProfissionalSelecaoDTO;
import com.pet.buscaativa.entities.dto.UsuarioReferenciaDTO;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.mapping.UsuarioMapper;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.UsuarioService;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import com.pet.buscaativa.services.exceptions.ValidationException;
import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsuarioServiceImpl implements UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioMapper usuarioMapper;
    private final PasswordEncoder passwordEncoder;
    private final PacienteRepository pacienteRepository;
    private final UsfReferenciaRepository usfReferenciaRepository;
    private final EscopoUnidadeService escopoUnidadeService;

    @Override
    public UsuarioDTO save(UsuarioDTO usuarioDTO) {
        Usuario usuarioSalvar = null;
        if (usuarioDTO.idPublico() != null) {
            usuarioSalvar = usuarioRepository.findByIdPublico(usuarioDTO.idPublico())
                    .filter(escopoUnidadeService::usuarioPertenceAoEscopo)
                    .orElseThrow(() -> new ResourceNotFoundException(usuarioDTO.idPublico()));
        }

        UsfReferencia usfAtuacao = resolverUsfAtuacao(usuarioDTO);
        validarUnidadeDestino(usuarioDTO);
        validarEmailDuplicado(usuarioDTO.email(), usuarioDTO.idPublico());

        // Senha é obrigatória apenas no cadastro (idPublico == null)
        boolean isCadastro = usuarioDTO.idPublico() == null;

        if (isCadastro && (usuarioDTO.senha() == null || usuarioDTO.senha().isBlank())) {
            throw new ValidationException("O campo de senha não pode ficar vazio.");
        }

        if (usuarioSalvar != null) {
            usuarioSalvar.setNome(usuarioDTO.nome());
            usuarioSalvar.setEmail(usuarioDTO.email());
            usuarioSalvar.setTipoUsuario(usuarioDTO.tipoUsuario());
            usuarioSalvar.setUnidadeAtuacao(usuarioDTO.unidadeAtuacao());

        } else {
            usuarioSalvar = usuarioMapper.toUsuarioEntity(usuarioDTO);

            if (usuarioRepository.count() == 0) {
                usuarioSalvar.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
            }
        }

        if (usuarioDTO.senha() != null && !usuarioDTO.senha().isBlank()) {
            usuarioSalvar.setSenha(passwordEncoder.encode(usuarioDTO.senha()));
        }

        usuarioSalvar.setUsfAtuacao(usfAtuacao);
        usuarioSalvar = usuarioRepository.save(usuarioSalvar);

        return usuarioMapper.toUsuarioDTO(usuarioSalvar);
    }

    private void validarUnidadeDestino(UsuarioDTO dto) {
        // Carga inicial e fixtures sem autenticação não passam pelo fluxo HTTP administrativo.
        if (SecurityContextHolder.getContext().getAuthentication() == null) return;
        escopoUnidadeService.validarUnidadeConfigurada();
        Usuario logado = escopoUnidadeService.usuarioLogado();
        if (dto.unidadeAtuacao() != logado.getUnidadeAtuacao()
                || dto.unidadeAtuacao() == UnidadeAtuacao.USF
                && !Objects.equals(dto.usfAtuacaoId(), logado.getUsfAtuacao().getId())) {
            throw new ValidationException("Só é possível salvar usuários para a sua própria unidade.");
        }
    }

    private UsfReferencia resolverUsfAtuacao(UsuarioDTO dto) {
        if (dto.unidadeAtuacao() != UnidadeAtuacao.USF) return null;
        if (dto.usfAtuacaoId() == null) {
            throw new ValidationException("Selecione a USF específica do usuário.");
        }
        return usfReferenciaRepository.findById(dto.usfAtuacaoId())
                .orElseThrow(() -> new ValidationException("A USF selecionada não existe."));
    }

    @Override
    public List<UsuarioDTO> findAll() {
        List<Usuario> list = usuarioRepository.findAll(escopoUnidadeService.<Usuario>specificationUsuario(),
                org.springframework.data.domain.Sort.by("nome"));
        return list.stream()
                .map(UsuarioDTO::new)
                .toList();
    }

    @Override
    public List<ProfissionalSelecaoDTO> listarProfissionaisParaSelecao() {
        return usuarioRepository.findAll(escopoUnidadeService.<Usuario>specificationUsuario()
                .and((r, q, cb) -> r.get("tipoUsuario").in(TipoUsuario.PROFISSIONAL, TipoUsuario.ADMINISTRADOR)),
                org.springframework.data.domain.Sort.by("nome")).stream()
                .map(ProfissionalSelecaoDTO::new)
                .toList();
    }

    @Override
    public List<UsuarioReferenciaDTO> listarUsuariosElegiveisParaReferencia() {
        return usuarioRepository.findAll(escopoUnidadeService.<Usuario>specificationUsuario()
                .and((r, q, cb) -> cb.notEqual(r.get("tipoUsuario"), TipoUsuario.RECEPCAO)),
                org.springframework.data.domain.Sort.by("nome")).stream()
                .map(UsuarioReferenciaDTO::new)
                .toList();
    }

    @Override
    public UsuarioDTO findById(UUID idPublic) {
        Usuario usuario = usuarioRepository.findByIdPublico(idPublic)
                .filter(escopoUnidadeService::usuarioPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException(idPublic));
        return new UsuarioDTO(usuario);
    }

    public void validarEmailDuplicado(String email, UUID idPublic) {
        usuarioRepository.findByEmail(email).ifPresent(
                usuario -> {
                    // Se o email já existe, mas pertence a um usuário DIFERENTE, lança exceção
                    if (!usuario.getIdPublico().equals(idPublic)) {
                        throw new RecursoDuplicadoException("E-mail já cadastrado no sistema!");
                    }
                    // Se pertence ao mesmo usuário (idPublic é igual), permite continuar
                });
    }

    @Override
    public UsuarioDTO findByEmail(String email) {
        var usuarioEntity = usuarioRepository.findByEmail(email).orElse(null);
        if (usuarioEntity == null) {
            return null;
        }

        return usuarioMapper.toUsuarioDTO(usuarioEntity);
    }

    @Override
    public void removerUsuario(UUID idPublico) {
        var usuarioEntity = usuarioRepository.findByIdPublico(idPublico)
                .filter(escopoUnidadeService::usuarioPertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException(idPublico));
        if (pacienteRepository.existsByProfissionalReferenciaId(usuarioEntity.getId())) {
            throw new ValidationException("Usuário vinculado como profissional de referência não pode ser removido."); 
        }else {
            usuarioRepository.deleteById(usuarioEntity.getId());
        }

    }

}

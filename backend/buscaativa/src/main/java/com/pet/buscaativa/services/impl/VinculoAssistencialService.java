package com.pet.buscaativa.services.impl;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Fundacao interna para vinculos EXPLICITOS. Sem endpoints nesta entrega.
 *
 * Nao alterar a verificacao de acesso clinico legada aqui: endpoints, filtros,
 * historico e indicadores so passarao ao novo modelo de forma coordenada,
 * apos migracao comprovada dos registros antigos.
 */
@Service
@RequiredArgsConstructor
public class VinculoAssistencialService {
    private final EscopoUnidadeService escopo;
    private final PacienteRepository pacientes;
    private final VinculoAssistencialRepository vinculos;

    /**
     * Cria um vinculo exclusivamente para a unidade autenticada, nunca para
     * a USF/CAPS citada nos campos de referencia do cadastro da pessoa.
     * A mesma identidade pode ter vinculos independentes em varias unidades.
     *
     * Uma rota futura devera usar fluxo de localizacao segura por CPF/CNS
     * e retornar apenas DTO cadastral minimo, nunca uma entidade Paciente.
     */
    @Transactional
    public VinculoAssistencial vincularExplicitamente(UUID idPublico) {
        Usuario ator = usuarioComPermissaoDeVincular();
        if (idPublico == null) throw new ResourceNotFoundException("Paciente não encontrado.");
        String chave = chaveUnidade(ator);

        // Serializar na identidade existente evita duplicacao em pedidos
        // concorrentes da mesma unidade; a constraint unica e defesa adicional.
        Paciente paciente = pacientes.findByIdPublicoForUpdate(idPublico)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));

        return vinculos.findByPacienteIdAndUnidadeChave(paciente.getId(), chave)
                .orElseGet(() -> vinculos.save(ator.getUnidadeAtuacao() == UnidadeAtuacao.USF
                        ? VinculoAssistencial.paraUsf(paciente, ator.getUsfAtuacao())
                        : VinculoAssistencial.paraCaps(paciente, ator.getUnidadeAtuacao())));
    }

    /**
     * Consulta interna: so localiza o vinculo da unidade do usuario logado.
     * Nao usar como autorizador de consultas clinicas enquanto persistir o
     * acesso legado por capsReferencia.
     */
    @Transactional(readOnly = true)
    public Optional<VinculoAssistencial> consultarVinculoDaUnidade(UUID idPublico) {
        Usuario ator = usuarioComPermissaoDeVincular();
        if (idPublico == null) return Optional.empty();
        return pacientes.findByIdPublico(idPublico)
                .flatMap(p -> vinculos.findByPacienteIdAndUnidadeChave(p.getId(), chaveUnidade(ator)));
    }

    private Usuario usuarioComPermissaoDeVincular() {
        escopo.validarUnidadeConfigurada();
        Usuario ator = escopo.usuarioLogado();
        if (ator.getTipoUsuario() != TipoUsuario.ADMINISTRADOR
                && ator.getTipoUsuario() != TipoUsuario.RECEPCAO
                && ator.getTipoUsuario() != TipoUsuario.PROFISSIONAL)
            throw new AccessDeniedException("Perfil não autorizado a criar vínculo assistencial.");
        return ator;
    }

    private String chaveUnidade(Usuario ator) {
        return ator.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? "USF:" + ator.getUsfAtuacao().getId()
                : ator.getUnidadeAtuacao().name();
    }
}

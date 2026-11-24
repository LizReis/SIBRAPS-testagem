package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Ensaio de escrita local, exclusivamente no perfil de teste explícito.
 * Não é chamado pelo cadastro, rotas clínicas, seed ou diagnóstico read-only.
 * Não migra estado global, não resolve o legado, não aplica indicadores.
 */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class EstadoClinicoLocalEnsaioService {
    private final EscopoUnidadeService escopo;
    private final UsuarioRepository usuarios;
    private final VinculoAssistencialRepository vinculos;
    private final HistoricoPacienteRepository historicos;
    private final Clock clock;

    public record Resultado(UUID vinculo, StatusPaciente status, TipoAcompanhamento tipo, Integer versao) {}

    @Transactional
    public Resultado configurar(UUID paciente, UUID vinculo, Integer versao,
            TipoAcompanhamento tipo, UUID referencia) {
        // Restrição conservadora deste ensaio, não uma nova política de edição HTTP.
        Usuario ator = ator(true);
        VinculoAssistencial local = bloquear(paciente, vinculo, versao, ator);
        if (tipo == null) throw new ValidationException("Tipo de acompanhamento obrigatório.");
        Usuario profissional = referencia == null ? null : usuarios.findByIdPublico(referencia)
                .filter(u -> u.getTipoUsuario() == TipoUsuario.ADMINISTRADOR
                        || u.getTipoUsuario() == TipoUsuario.PROFISSIONAL)
                .filter(u -> pertence(u, local.getUnidadeChave()))
                .orElseThrow(() -> new ResourceNotFoundException("Profissional de referência não encontrado."));
        Long anterior = local.getProfissionalReferencia() == null ? null : local.getProfissionalReferencia().getId();
        Long novo = profissional == null ? null : profissional.getId();
        if (tipo == local.getTipoAcompanhamento() && Objects.equals(anterior, novo)) return resultado(local);
        String descricao = "Acompanhamento local: " + local.getTipoAcompanhamento() + " -> " + tipo
                + ". Profissional de referência " + (Objects.equals(anterior, novo) ? "mantido." : "alterado.");
        local.definirAcompanhamentoLocal(tipo, profissional);
        return registrar(local, ator, descricao);
    }

    @Transactional
    public Resultado encerrar(UUID paciente, UUID vinculo, Integer versao, EncerramentoPacienteDTO dados) {
        Usuario ator = ator(false);
        VinculoAssistencial local = bloquear(paciente, vinculo, versao, ator);
        if (dados == null || dados.motivo() == null || dados.statusPaciente() == null
                || dados.statusPaciente() == StatusPaciente.ATIVO || dados.statusPaciente() == local.getStatusPaciente())
            throw new ValidationException("Encerramento local inválido.");
        if (dados.motivo() == MotivoEncerramento.OUTRO
                && (dados.descricao() == null || dados.descricao().isBlank()))
            throw new ValidationException("Descrição obrigatória para encerramento OUTRO.");
        String descricao = "Encerramento local: " + local.getStatusPaciente() + " -> " + dados.statusPaciente()
                + ". Motivo: " + dados.motivo()
                + (dados.descricao() == null || dados.descricao().isBlank() ? "." : ". " + dados.descricao().trim());
        validarDescricao(descricao);
        local.alterarStatusLocal(dados.statusPaciente());
        return registrar(local, ator, descricao);
    }

    @Transactional
    public Resultado reativar(UUID paciente, UUID vinculo, Integer versao, String motivo) {
        Usuario ator = ator(true);
        VinculoAssistencial local = bloquear(paciente, vinculo, versao, ator);
        if (local.getStatusPaciente() == null || local.getStatusPaciente() == StatusPaciente.ATIVO)
            throw new ValidationException("Reativação exige status local não ativo registrado.");
        if (motivo == null || motivo.isBlank()) throw new ValidationException("Motivo de reativação obrigatório.");
        String descricao = "Reativação local: " + local.getStatusPaciente() + " -> ATIVO. Motivo: " + motivo.trim();
        validarDescricao(descricao);
        local.alterarStatusLocal(StatusPaciente.ATIVO);
        return registrar(local, ator, descricao);
    }

    private Usuario ator(boolean somenteAdministrador) {
        escopo.validarUnidadeConfigurada();
        Usuario principal = escopo.usuarioLogado();
        if (principal.getId() == null) throw new AccessDeniedException("Usuário não identificado.");
        Usuario atual = usuarios.findById(principal.getId())
                .orElseThrow(() -> new AccessDeniedException("Usuário não identificado."));
        if (!pertence(atual, ChaveUnidadeAssistencial.doUsuarioAtual(principal))
                || atual.getTipoUsuario() != TipoUsuario.ADMINISTRADOR
                   && (somenteAdministrador || atual.getTipoUsuario() != TipoUsuario.PROFISSIONAL))
            throw new AccessDeniedException("Perfil ou unidade não autorizado.");
        return atual;
    }

    private static boolean pertence(Usuario usuario, String unidade) {
        try {
            return unidade.equals(ChaveUnidadeAssistencial.doUsuarioAtual(usuario));
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private VinculoAssistencial bloquear(UUID paciente, UUID vinculo, Integer versao, Usuario ator) {
        if (paciente == null || vinculo == null) throw new ResourceNotFoundException("Vínculo não encontrado.");
        String unidade = ChaveUnidadeAssistencial.doUsuarioAtual(ator);
        var local = vinculos.findLocalForEstadoUpdate(vinculo, paciente, unidade)
                .orElseThrow(() -> new ResourceNotFoundException("Vínculo não encontrado."));
        if (!unidade.equals(ChaveUnidadeAssistencial.doVinculo(local.getUnidadeAtuacao(), local.getUsfAtuacao())))
            throw new ResourceNotFoundException("Vínculo não encontrado.");
        if (versao == null || !versao.equals(local.getVersion()))
            throw new ValidationException("Versão local divergente. Consulte o vínculo novamente.");
        return local;
    }

    private Resultado registrar(VinculoAssistencial local, Usuario ator, String descricao) {
        validarDescricao(descricao);
        var h = new HistoricoPaciente();
        h.setPaciente(local.getPaciente());
        h.setProfissional(ator);
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.setOcorridoEm(LocalDateTime.now(clock));
        h.setDescricao(descricao);
        h.registrarOrigemDeAcaoLocal(ator);
        historicos.save(h);
        vinculos.saveAndFlush(local);
        return resultado(local);
    }

    private static void validarDescricao(String descricao) {
        if (descricao.length() > 2000) throw new ValidationException("Descrição excede o limite do histórico.");
    }

    private static Resultado resultado(VinculoAssistencial local) {
        return new Resultado(local.getIdPublico(), local.getStatusPaciente(), local.getTipoAcompanhamento(), local.getVersion());
    }
}

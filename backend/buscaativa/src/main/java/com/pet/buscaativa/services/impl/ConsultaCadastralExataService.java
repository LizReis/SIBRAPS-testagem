package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.utils.DocumentoUtil;

import lombok.RequiredArgsConstructor;

/**
 * Pre-conferencia INTERNA, apenas cadastral, antes de reutilizar uma identidade.
 *
 * Aceita exclusivamente CPF/CNS exatos informados por usuario autenticado
 * com unidade configurada. Nao concede vinculo clinico, nao cria pacientes
 * nem expoe historicos, status de acompanhamento, riscos, referencias
 * territoriais ou UUID global pela consulta.
 *
 * SEM endpoint e sem alteracao do cadastro legado nesta etapa.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsultaCadastralExataService {
    private final EscopoUnidadeService escopo;
    private final PacienteRepository pacientes;

    /** Minimo necessario para confirmacao humana de identidade cadastral. */
    public record IdentidadeCadastral(
            String nome,
            String nomeMae,
            LocalDate dataNascimento) {}

    public IdentidadeCadastral localizar(String cpfInformado, String cnsInformado) {
        // Nao acessar indices globais antes da verificacao de perfil/unidade.
        escopo.validarUnidadeConfigurada();
        var usuario = escopo.usuarioLogado();
        TipoUsuario perfil = usuario == null ? null : usuario.getTipoUsuario();
        if (perfil != TipoUsuario.ADMINISTRADOR
                && perfil != TipoUsuario.RECEPCAO
                && perfil != TipoUsuario.PROFISSIONAL)
            throw new AccessDeniedException("Perfil sem permissao para consulta cadastral.");

        String cpf = VinculacaoIdentidadeExistenteService.normalizarCpf(cpfInformado);
        String cns = VinculacaoIdentidadeExistenteService.normalizarCns(cnsInformado);
        if (cpf == null && cns == null)
            throw new ValidationException("Informe CPF ou CNS para localizacao exata.");

        Optional<Paciente> encontradoCpf = cpf == null ? Optional.empty() : pacientes.findByCpf(cpf);
        Optional<Paciente> encontradoCns = cns == null ? Optional.empty() : pacientes.findByCns(cns);
        if (encontradoCpf.isPresent() && encontradoCns.isPresent()
                && !Objects.equals(encontradoCpf.get().getId(), encontradoCns.get().getId()))
            throw conflito();

        Paciente paciente = encontradoCpf.or(() -> encontradoCns)
                .orElseThrow(() -> new ResourceNotFoundException("Cadastro nao localizado."));

        // CPF e CNS informados devem apontar exatamente para a MESMA pessoa.
        // Nao fazer uniao de identidades nem corrigir documentos aqui.
        if ((cpf != null && !cpf.equals(DocumentoUtil.normalizarCPF(paciente.getCpf())))
                || (cns != null && !cns.equals(DocumentoUtil.normalizarCNS(paciente.getCns()))))
            throw conflito();

        // Este retorno jamais inclui o UUID global. O passo de vinculacao
        // deve revalidar documentos e identidade em transacao com lock proprio.
        return new IdentidadeCadastral(
                paciente.getNome(), paciente.getNomeMae(), paciente.getDataNascimento());
    }

    private static ValidationException conflito() {
        return new ValidationException(
                "Documentos inconsistentes. E necessaria revisao cadastral autorizada.");
    }
}

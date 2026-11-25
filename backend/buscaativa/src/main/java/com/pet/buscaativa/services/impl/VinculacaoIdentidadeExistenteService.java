package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.utils.DocumentoUtil;

import lombok.RequiredArgsConstructor;

/**
 * Primeira etapa INTERNA do reaproveitamento seguro do cadastro unico.
 *
 * Busca apenas por CPF/CNS EXATOS; nunca lista pacientes de outras unidades
 * nem retorna informacoes clinicas ou vinculacoes externas. Se a identidade
 * ja existir, cria somente o vinculo assistencial da unidade autenticada.
 * CPF/CNS conflitantes precisam de revisao humana, nao de mescla automatica.
 *
 * Sem endpoint e sem integracao com o cadastro legado nesta fase: os filtros
 * clinicos antigos nao devem ser migrados de forma parcial.
 */
@Service
@RequiredArgsConstructor
public class VinculacaoIdentidadeExistenteService {
    private final EscopoUnidadeService escopo;
    private final ModeloClinico modelo;
    private final VinculoAssistencialService vinculos;

    /** Identificadores internos minimos; nenhum atributo assistencial ou de outra unidade. */
    public record Resultado(UUID pacienteIdPublico, UUID vinculoLocalIdPublico,
            String unidadeChave) {}

    @Transactional
    public Resultado vincularPorDocumentos(String cpfInformado, String cnsInformado) {
        return vincularPorDocumentosCom(cpfInformado, cnsInformado, vinculos::vincularExplicitamente);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Resultado vincularPorDocumentosCom(String cpfInformado, String cnsInformado,
            java.util.function.Function<UUID, VinculoAssistencial> criarVinculo) {
        // A verificacao de acesso vem ANTES de qualquer pesquisa global.
        escopo.validarUnidadeConfigurada();
        Usuario ator = escopo.usuarioLogado();
        TipoUsuario perfil = ator == null ? null : ator.getTipoUsuario();
        if (perfil != TipoUsuario.ADMINISTRADOR
                && perfil != TipoUsuario.RECEPCAO
                && perfil != TipoUsuario.PROFISSIONAL)
            throw new AccessDeniedException("Perfil sem permissao para vincular paciente.");

        String cpf = normalizarCpf(cpfInformado);
        String cns = normalizarCns(cnsInformado);
        if (cpf == null && cns == null)
            throw new ValidationException("Informe CPF ou CNS para localizacao exata.");

        Optional<Paciente> porCpf = cpf == null ? Optional.empty() : modelo.porCpf(cpf);
        Optional<Paciente> porCns = cns == null ? Optional.empty() : modelo.porCns(cns);

        if (porCpf.isPresent() && porCns.isPresent()
                && !Objects.equals(porCpf.get().getId(), porCns.get().getId()))
            throw conflito();

        Paciente identidade = porCpf.or(() -> porCns)
                .orElseThrow(() -> new ResourceNotFoundException("Cadastro nao localizado."));

        // Se dois documentos forem informados, ambos devem estar coerentes
        // com o MESMO cadastro. Nao associar por apenas um documento quando
        // o outro for desconhecido, divergente ou pertencer a outra pessoa.
        exigirCorrespondencia(identidade, cpf, cns);

        // O servico existente faz lock da identidade e garante UNIQUE para
        // paciente + unidade, sem inferir vinculo via caps/usf de referencia.
        VinculoAssistencial local = criarVinculo.apply(identidade.getIdPublico());

        // Revalidacao sob o lock, ainda dentro da transacao: evita associar
        // um CPF/CNS que tenha sido alterado entre pesquisa e gravacao.
        Paciente identidadeBloqueada = local.getPaciente();
        if (identidadeBloqueada == null
                || !Objects.equals(identidade.getId(), identidadeBloqueada.getId()))
            throw conflito();
        exigirCorrespondencia(identidadeBloqueada, cpf, cns);

        return new Resultado(identidadeBloqueada.getIdPublico(),
                local.getIdPublico(), local.getUnidadeChave());
    }

    private static void exigirCorrespondencia(Paciente paciente, String cpf, String cns) {
        if ((cpf != null && !cpf.equals(DocumentoUtil.normalizarCPF(paciente.getCpf())))
                || (cns != null && !cns.equals(DocumentoUtil.normalizarCNS(paciente.getCns()))))
            throw conflito();
    }

    static String normalizarCpf(String informado) {
        if (informado == null || informado.isBlank()) return null;
        // Aceitar somente a pontuacao prevista para CPF, sem descartar
        // letras ou outros caracteres arbitrarios e tornar a busca ambigua.
        boolean caracteresPermitidos = informado.codePoints()
                .allMatch(c -> (c >= '0' && c <= '9') || c == '.' || c == '-'
                        || Character.isWhitespace(c));
        String normalizado = DocumentoUtil.normalizarCPF(informado);
        if (!caracteresPermitidos || normalizado.length() != 11)
            throw new ValidationException("Formato de CPF invalido.");
        return normalizado;
    }

    static String normalizarCns(String informado) {
        if (informado == null || informado.isBlank()) return null;
        boolean caracteresPermitidos = informado.codePoints()
                .allMatch(c -> (c >= '0' && c <= '9') || Character.isWhitespace(c));
        String normalizado = DocumentoUtil.normalizarCNS(informado);
        if (!caracteresPermitidos || normalizado.length() != 15)
            throw new ValidationException("Formato de CNS invalido.");
        return normalizado;
    }

    private static ValidationException conflito() {
        return new ValidationException(
                "Documentos inconsistentes. E necessaria revisao cadastral autorizada.");
    }
}

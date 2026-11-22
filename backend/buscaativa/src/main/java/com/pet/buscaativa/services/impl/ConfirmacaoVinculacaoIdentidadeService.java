package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * Passo INTERNO de confirmacao explicita de identidade antes do vinculo local.
 *
 * Consulta apenas dados demograficos minimos por CPF/CNS exatos, exige
 * confirmacao humana de nome, nome da mae e nascimento e so entao cria
 * o vinculo com a unidade autenticada.
 *
 * A consulta, a criacao do vinculo e a reconferencia sob lock acontecem
 * em UMA transacao. Se qualquer verificacao falhar, a operacao inteira
 * reverte, inclusive um vinculo recem-criado.
 *
 * NAO possui controller/rota. Nao altera a identidade global, as referencias
 * territoriais ou o prontuario; nao libera leitura clinica de outra unidade.
 */
@Service
@RequiredArgsConstructor
public class ConfirmacaoVinculacaoIdentidadeService {
    private final ConsultaCadastralExataService consulta;
    private final VinculacaoIdentidadeExistenteService vinculacao;
    private final PacienteRepository pacientes;

    @Transactional
    public VinculacaoIdentidadeExistenteService.Resultado confirmarEVincular(
            String cpfInformado,
            String cnsInformado,
            String nomeConfirmado,
            String nomeMaeConfirmada,
            LocalDate nascimentoConfirmado) {
        if (normalizarNome(nomeConfirmado) == null
                || normalizarNome(nomeMaeConfirmada) == null
                || nascimentoConfirmado == null)
            throw new ValidationException("Confirmacao cadastral completa obrigatoria.");

        // A consulta autoriza o usuario e a unidade ANTES de acessar indices
        // globais. Ela nao revela unidade de terceiro nem dados clinicos.
        var dados = consulta.localizar(cpfInformado, cnsInformado);
        exigirConfirmacao(dados.nome(), dados.nomeMae(), dados.dataNascimento(),
                nomeConfirmado, nomeMaeConfirmada, nascimentoConfirmado);

        // Repete pesquisa documental dentro do servico transacional existente
        // e serializa a vinculacao na identidade global com lock.
        var resultado = vinculacao.vincularPorDocumentos(cpfInformado, cnsInformado);

        // Defesa contra alteracao dos dados entre pre-conferencia e escrita:
        // a identidade continua bloqueada ate o commit da transacao externa.
        Paciente bloqueado = pacientes.findByIdPublicoForUpdate(resultado.pacienteIdPublico())
                .orElseThrow(() -> new ResourceNotFoundException("Cadastro nao localizado."));
        if (!Objects.equals(bloqueado.getIdPublico(), resultado.pacienteIdPublico()))
            throw conflito();
        exigirConfirmacao(bloqueado.getNome(), bloqueado.getNomeMae(),
                bloqueado.getDataNascimento(), nomeConfirmado, nomeMaeConfirmada,
                nascimentoConfirmado);
        return resultado;
    }

    private static void exigirConfirmacao(String nome, String mae, LocalDate nascimento,
            String nomeConfirmado, String maeConfirmada, LocalDate nascimentoConfirmado) {
        if (!Objects.equals(normalizarNome(nome), normalizarNome(nomeConfirmado))
                || !Objects.equals(normalizarNome(mae), normalizarNome(maeConfirmada))
                || !Objects.equals(nascimento, nascimentoConfirmado))
            throw conflito();
    }

    /** Normaliza apenas caixa e espacos, sem casamento aproximado de nomes. */
    private static String normalizarNome(String texto) {
        if (texto == null || texto.isBlank()) return null;
        return texto.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static ValidationException conflito() {
        return new ValidationException(
                "Confirmacao cadastral divergente. Solicite revisao autorizada.");
    }
}

package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;

/** Composição dos caminhos cadastrais existentes em ambiente de ensaio.
 * Não expõe endpoint, não ativa o corte e não altera a autorização da recepção no legado.
 */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class CadastroAcompanhamentoLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final PacienteServiceImpl cadastro;
    private final ConfirmacaoVinculacaoIdentidadeService confirmacao;
    private final VinculacaoIdentidadeExistenteService vinculacao;
    private final InicioAcompanhamentoLocalEnsaioService inicio;
    private final VinculoAssistencialRepository vinculos;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final DocumentosCadastroLocalLocks documentos;

    public record CadastroLocal(UUID paciente, UUID vinculo, Integer versaoCadastro, Integer versaoVinculo) {}

    @Transactional
    public PacienteDTO cadastrarNovo(PacienteDTO dados, boolean ignorarSimilaridade) {
        atores.autorizarOperacional();
        if (dados == null || dados.idPublico() != null || dados.tipoAcompanhamento() == null)
            throw new ValidationException("Cadastro novo com tipo de acompanhamento obrigatório.");
        documentos.bloquear(dados.cpf(), dados.cns());
        return cadastro.salvarNovoComVinculacao(dados, ignorarSimilaridade,
                id -> inicio.iniciarPorCadastro(id, dados.tipoAcompanhamento(), dados.profissionalReferenciaId()));
    }

    @Transactional
    public CadastroLocal cadastrarNovoComIdentificadores(PacienteDTO dados, boolean ignorarSimilaridade) {
        var novo = cadastrarNovo(dados, ignorarSimilaridade);
        var contexto = atores.autorizarOperacional();
        var local = vinculos.findByPacienteIdPublicoAndUnidadeChave(novo.idPublico(), contexto.unidade()).orElseThrow();
        return resposta(local);
    }

    @Transactional
    public CadastroLocal editarCadastro(UUID paciente, UUID vinculo, Integer versaoCadastro,
            PacienteDTO dados, boolean ignorarSimilaridade) {
        var contexto = atores.autorizarOperacional();
        if (dados == null || dados.idPublico() != null && !dados.idPublico().equals(paciente))
            throw new ValidationException("Dados cadastrais incompatíveis.");
        documentos.bloquear(dados.cpf(), dados.cns());
        var recursos = coordenacao.paraVinculo(contexto, paciente, vinculo, null);
        var local = recursos.vinculos().get(paciente);
        var pessoa = local.getPaciente();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versaoCadastro, pessoa.getVersion());
        cadastro.editarCadastroCompartilhado(dados, pessoa, ignorarSimilaridade);
        return resposta(local);
    }

    private static CadastroLocal resposta(VinculoAssistencial local) {
        return new CadastroLocal(local.getPaciente().getIdPublico(), local.getIdPublico(),
                local.getPaciente().getVersion(), local.getVersion());
    }

    @Transactional
    public VinculacaoIdentidadeExistenteService.Resultado confirmarEVincular(
            String cpf, String cns, String nome, String mae, LocalDate nascimento,
            TipoAcompanhamento tipo, UUID referencia) {
        atores.autorizarOperacional();
        if (tipo == null) throw new ValidationException("Tipo de acompanhamento obrigatório.");
        documentos.bloquear(cpf, cns);
        return confirmacao.confirmarComVinculacao(cpf, cns, nome, mae, nascimento,
                (c, n) -> vinculacao.vincularPorDocumentosCom(c, n, id -> criar(id, tipo, referencia)));
    }

    private VinculoAssistencial criar(UUID id, TipoAcompanhamento tipo, UUID referencia) {
        var resultado = inicio.iniciarPorCadastro(id, tipo, referencia);
        return vinculos.findByIdPublico(resultado.vinculo()).orElseThrow();
    }
}

package br.sibraps.modelovinculo;

import java.time.LocalDate;
import java.util.UUID;


import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.dto.AdicionarParticipanteDTO;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.dto.AtualizarGrupoTerapeuticoDTO;
import com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO;
import com.pet.buscaativa.entities.dto.CorrigirFrequenciaDTO;
import com.pet.buscaativa.entities.dto.CorrigirFrequenciasGrupoDTO;
import com.pet.buscaativa.entities.dto.CriarGrupoDTO;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.dto.InscricaoFuturaGrupoDTO;
import com.pet.buscaativa.entities.dto.InscricaoRetroativaGrupoDTO;
import com.pet.buscaativa.entities.dto.NovaSessaoDTO;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.dto.ReativacaoPacienteDTO;
import com.pet.buscaativa.entities.dto.RecuperarReservaCanceladaDTO;
import com.pet.buscaativa.entities.dto.RegistrarPresencaGrupoDTO;
import com.pet.buscaativa.entities.dto.RegistroHistoricoPacienteDTO;
import com.pet.buscaativa.entities.dto.RegularizarFrequenciaCanceladaDTO;
import com.pet.buscaativa.entities.dto.SessaoGrupoDTO;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.impl.CadastroAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.services.impl.ComandosAssistenciaisLocaisEnsaioService;
import com.pet.buscaativa.services.impl.ComandosEstadoLocalEnsaioService;
import com.pet.buscaativa.services.impl.DtosAssistenciaisLocaisEnsaioService;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaColetivaLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaIndividualLocalEnsaioService;
import com.pet.buscaativa.services.impl.HistoricoManualLocalEnsaioService;
import com.pet.buscaativa.services.impl.ReservaIndividualLocalEnsaioService;
import com.pet.buscaativa.services.impl.RevisaoProvenienciaLocalEnsaioService;
import com.pet.buscaativa.services.impl.SerieGruposLocaisEnsaioService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;


public final class EscritoresVinculoController {
    public static final String RAIZ = "${sibraps.clinica.raiz:/api}";

    private EscritoresVinculoController() {}

    private static final String CLINICO = "hasAnyRole('ADMINISTRADOR','PROFISSIONAL')";
    private static final String OPERACIONAL = "hasAnyRole('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')";
    private static final String CADASTRAL = "hasAnyRole('ADMINISTRADOR','RECEPCAO')";
    private static final String ADMIN = "hasRole('ADMINISTRADOR')";

    @RestController
    @RequestMapping(RAIZ + "/pacientes")
    @RequiredArgsConstructor
    public static class Cadastro {
        private final CadastroAcompanhamentoLocalEnsaioService cadastro;
        private final ComandosEstadoLocalEnsaioService estado;
        private final HistoricoManualLocalEnsaioService historico;
        private final RevisaoProvenienciaLocalEnsaioService revisao;

        public record Confirmacao(String cpf, String cns, String nome, String nomeMae, LocalDate dataNascimento,
                @NotNull TipoAcompanhamento tipoAcompanhamento, UUID referencia) {}
        public record Configuracao(@NotNull TipoAcompanhamento tipoAcompanhamento, UUID referencia) {}
        public record Solicitar(@NotNull PendenciaRevisaoProveniencia.TipoRegistro tipo, @NotNull Long registro,
                String justificativa) {}
        public record Decidir(@NotNull DecisaoRevisaoProveniencia.Decisao decisao, String justificativa) {}

        @PostMapping
        @PreAuthorize(OPERACIONAL)
        public ResponseEntity<CadastroAcompanhamentoLocalEnsaioService.CadastroLocal> cadastrar(
                @RequestBody @Valid PacienteDTO dados, @RequestParam(defaultValue = "false") boolean ignorarSimilaridade) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(cadastro.cadastrarNovoComIdentificadores(dados, ignorarSimilaridade));
        }

        @PostMapping("/cadastro-unico/confirmar-vinculo")
        @PreAuthorize(OPERACIONAL)
        public ResponseEntity<Void> confirmar(@RequestBody @Valid Confirmacao dados) {
            cadastro.confirmarEVincular(dados.cpf(), dados.cns(), dados.nome(), dados.nomeMae(), dados.dataNascimento(),
                    dados.tipoAcompanhamento(), dados.referencia());
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").build();
        }

        @PutMapping("/{paciente}/vinculos/{vinculo}/cadastro")
        @PreAuthorize(OPERACIONAL)
        public CadastroAcompanhamentoLocalEnsaioService.CadastroLocal editar(@PathVariable UUID paciente,
                @PathVariable UUID vinculo, @RequestParam Integer versaoCadastro, @RequestBody @Valid CadastroCompartilhadoHttp dados,
                @RequestParam(defaultValue = "false") boolean ignorarSimilaridade) {
            return cadastro.editarCadastro(paciente, vinculo, versaoCadastro, dados.dados(), ignorarSimilaridade);
        }

        @PatchMapping("/{paciente}/vinculos/{vinculo}/acompanhamento")
        @PreAuthorize(ADMIN)
        public EstadoClinicoLocalEnsaioService.Resultado configurar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version, @RequestBody @Valid Configuracao dados) {
            return estado.configurar(paciente, vinculo, version, dados.tipoAcompanhamento(), dados.referencia());
        }

        @PatchMapping("/{paciente}/vinculos/{vinculo}/encerrar")
        @PreAuthorize(CLINICO)
        public EstadoClinicoLocalEnsaioService.Resultado encerrar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version, @RequestBody @Valid EncerramentoPacienteDTO dados) {
            return estado.encerrar(paciente, vinculo, version, dados);
        }

        @PatchMapping("/{paciente}/vinculos/{vinculo}/reativar")
        @PreAuthorize(ADMIN)
        public EstadoClinicoLocalEnsaioService.Resultado reativar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version, @RequestBody @Valid ReativacaoPacienteDTO dados) {
            return estado.reativar(paciente, vinculo, version, dados);
        }

        @DeleteMapping("/{paciente}/vinculos/{vinculo}")
        @PreAuthorize(ADMIN)
        public EstadoClinicoLocalEnsaioService.Resultado inativar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version) {
            return estado.inativar(paciente, vinculo, version);
        }

        @PostMapping("/{paciente}/vinculos/{vinculo}/historico")
        @PreAuthorize(CLINICO)
        public ResponseEntity<Void> registrarHistorico(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version, @RequestBody @Valid RegistroHistoricoPacienteDTO dados) {
            historico.registrar(paciente, vinculo, version, dados);
            return ResponseEntity.noContent().build();
        }

        @org.springframework.web.bind.annotation.GetMapping("/{paciente}/vinculos/{vinculo}/revisoes/candidatos-origem")
        @PreAuthorize(ADMIN)
        @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
        public ResponseEntity<com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.CandidatosOrigem> candidatosOrigem(
                @PathVariable UUID paciente,@PathVariable UUID vinculo) {
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(revisao.candidatosOrigem(paciente,vinculo));
        }

        @PostMapping("/{paciente}/vinculos/{vinculo}/revisoes")
        @PreAuthorize(CLINICO)
        public UUID solicitar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
                @RequestParam Integer version, @RequestBody @Valid Solicitar dados) {
            return revisao.solicitar(paciente, vinculo, version, dados.tipo(), dados.registro(), dados.justificativa());
        }

        @PostMapping("/{paciente}/vinculos/{vinculo}/revisoes/{ticket}/decisao")
        @PreAuthorize(ADMIN)
        public UUID decidir(@PathVariable UUID paciente, @PathVariable UUID vinculo, @PathVariable UUID ticket,
                @RequestParam Integer version, @RequestBody @Valid Decidir dados) {
            return revisao.decidir(paciente, vinculo, version, ticket, dados.decisao(), dados.justificativa());
        }
    }

    @RestController
    @RequestMapping(RAIZ + "/agendamentos")
    @RequiredArgsConstructor
    @Transactional
    public static class Individual {
        private final ReservaIndividualLocalEnsaioService reservas;
        private final FrequenciaIndividualLocalEnsaioService frequencias;
        private final ComandosAssistenciaisLocaisEnsaioService comandos;
        private final DtosAssistenciaisLocaisEnsaioService dtos;

        @PostMapping
        @PreAuthorize(CADASTRAL)
        public ResponseEntity<AgendamentoDTO> criar(@RequestBody @Valid AgendamentoDTO dados) {
            Long id = dados.agendamentoOriginalId() == null ? reservas.criar(dados).atendimento()
                    : frequencias.remarcarPorCadastro(dados).atendimento();
            return ResponseEntity.status(HttpStatus.CREATED).body(dtos.atendimento(id));
        }

        @PatchMapping("/{id}/status")
        @PreAuthorize(OPERACIONAL)
        public AgendamentoDTO status(@PathVariable Long id, @RequestParam SituacaoAtendimento novoStatus,
                @RequestParam Integer version, @RequestParam(required = false) String justificativa) {
            comandos.atualizarStatusIndividual(id, novoStatus, version, justificativa);
            return dtos.atendimento(id);
        }

        @PatchMapping("/{id}/frequencia/correcao")
        @PreAuthorize(CLINICO)
        public AgendamentoDTO corrigir(@PathVariable Long id, @RequestBody @Valid CorrigirFrequenciaDTO dados) {
            comandos.corrigirIndividual(id, dados);
            return dtos.atendimento(id);
        }

        @PostMapping("/{id}/regularizacao-cancelamento/recuperar-reserva")
        @PreAuthorize(CLINICO)
        public AgendamentoDTO recuperar(@PathVariable Long id, @RequestBody @Valid RecuperarReservaCanceladaDTO dados) {
            comandos.recuperarReserva(id, dados);
            return dtos.atendimento(id);
        }

        @PostMapping("/{id}/regularizacao-cancelamento/frequencia")
        @PreAuthorize(CLINICO)
        public AgendamentoDTO regularizar(@PathVariable Long id, @RequestBody @Valid RegularizarFrequenciaCanceladaDTO dados) {
            comandos.regularizarIndividual(id, dados);
            return dtos.atendimento(id);
        }
    }

    @RestController
    @RequestMapping(RAIZ + "/grupos")
    @RequiredArgsConstructor
    @Transactional
    public static class Coletivo {
        private final SerieGruposLocaisEnsaioService series;
        private final FrequenciaColetivaLocalEnsaioService frequencias;
        private final ComandosAssistenciaisLocaisEnsaioService comandos;
        private final DtosAssistenciaisLocaisEnsaioService dtos;

        public record SerieHttp(@com.fasterxml.jackson.annotation.JsonUnwrapped
                com.pet.buscaativa.entities.dto.GrupoTerapeuticoDTO dados, Long grupo,
                Integer versaoGrupo, java.util.List<Long> sessoes) {}

        private SerieHttp resposta(SerieGruposLocaisEnsaioService.Resultado resultado) {
            return new SerieHttp(dtos.grupo(resultado.grupo()), resultado.grupo(), resultado.versaoGrupo(), resultado.sessoes());
        }

        @PostMapping
        @PreAuthorize(OPERACIONAL)
        public SerieHttp criar(@RequestBody @Valid CriarGrupoDTO dados) {
            return resposta(series.criar(dados));
        }

        @PutMapping("/{grupo}")
        @PreAuthorize(OPERACIONAL)
        public SerieHttp editar(@PathVariable Long grupo,
                @RequestBody @Valid AtualizarGrupoTerapeuticoDTO dados) {
            return resposta(series.atualizar(grupo, dados));
        }

        @PostMapping("/sessoes")
        @PreAuthorize(OPERACIONAL)
        public SessaoGrupoDTO proxima(@RequestBody @Valid NovaSessaoDTO dados) {
            return dtos.sessao(series.criarProxima(dados).sessao());
        }

        @PostMapping("/sessoes/{sessao}/participantes")
        @PreAuthorize(OPERACIONAL)
        public SessaoGrupoDTO adicionar(@PathVariable Long sessao, @RequestParam int version,
                @RequestBody @Valid AdicionarParticipanteDTO dados) {
            frequencias.adicionarParticipante(sessao, version, dados.pacienteId());
            return dtos.sessao(sessao);
        }

        @DeleteMapping("/sessoes/{sessao}/participantes/{paciente}")
        @PreAuthorize(OPERACIONAL)
        public SessaoGrupoDTO remover(@PathVariable Long sessao, @PathVariable UUID paciente, @RequestParam int version) {
            frequencias.removerParticipante(sessao, version, paciente);
            return dtos.sessao(sessao);
        }

        @PatchMapping("/sessoes/{sessao}/participantes/{paciente}/presenca")
        @PreAuthorize(CLINICO)
        public SessaoGrupoDTO provisoria(@PathVariable Long sessao, @PathVariable UUID paciente,
                @RequestParam int version, @RequestBody @Valid RegistrarPresencaGrupoDTO dados) {
            frequencias.registrarProvisoria(sessao, version, paciente, dados.statusPresenca());
            return dtos.sessao(sessao);
        }

        @PostMapping("/sessoes/{sessao}/confirmacao-ocorrencia")
        @PreAuthorize(CLINICO)
        public SessaoGrupoDTO confirmar(@PathVariable Long sessao, @RequestBody @Valid ConfirmarOcorrenciaGrupoDTO dados) {
            comandos.confirmarOcorrencia(sessao, dados);
            return dtos.sessao(sessao);
        }

        @PatchMapping("/sessoes/{sessao}/frequencias")
        @PreAuthorize(CLINICO)
        public SessaoGrupoDTO corrigir(@PathVariable Long sessao, @RequestBody @Valid CorrigirFrequenciasGrupoDTO dados) {
            comandos.corrigirColetiva(sessao, dados);
            return dtos.sessao(sessao);
        }

        @PatchMapping("/sessoes/{sessao}/status")
        @PreAuthorize(CLINICO)
        public SessaoGrupoDTO status(@PathVariable Long sessao, @RequestParam StatusSessaoGrupo novoStatus,
                @RequestParam(required = false) Integer version) {
            frequencias.conferirStatusAgendado(sessao, version, novoStatus);
            return dtos.sessao(sessao);
        }

        @PostMapping("/{grupo}/inscricoes-retroativas")
        @PreAuthorize(CLINICO)
        public java.util.List<Long> retroativa(@PathVariable Long grupo, @RequestBody @Valid InscricaoRetroativaGrupoDTO dados) {
            return series.inscreverRetroativamente(grupo, dados);
        }

        @PostMapping("/{grupo}/inscricoes-futuras")
        @PreAuthorize(CLINICO)
        public java.util.List<Long> futura(@PathVariable Long grupo, @RequestBody @Valid InscricaoFuturaGrupoDTO dados) {
            return series.inscreverFuturas(grupo, dados);
        }

        @DeleteMapping("/{grupo}/participantes/{paciente}")
        @PreAuthorize(CLINICO)
        public ResponseEntity<Void> removerFuturas(@PathVariable Long grupo, @PathVariable UUID paciente) {
            series.removerFuturas(grupo, paciente);
            return ResponseEntity.noContent().build();
        }
    }
}

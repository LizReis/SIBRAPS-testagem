package br.sibraps.modelovinculo;

import static org.springframework.http.CacheControl.noStore;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.impl.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(EscritoresVinculoController.RAIZ)
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL','RECEPCAO')")
public class ConsultasVinculoController {
    private final ConsultasClinicasLocaisEnsaioService pacientes;
    private final ConsultaCadastralExataService cadastro;
    private final DtosAssistenciaisLocaisEnsaioService fatos;
    private final BuscaAtivaPainelLocalEnsaioService painel;
    private final RelatoriosLocaisEnsaioService relatorios;
    private final PainelPendenciasLocalEnsaioService pendencias;
    private final AuditoriaBuscaLocalEnsaioService auditoria;
    private final Clock clock;

    record Documentos(String cpf, String cns) {}
    record Auditoria(UUID requisicao, Integer version, String destino, String descricao) {}

    @PostMapping("/pacientes/cadastro-unico/conferencia")
    ResponseEntity<ConsultaCadastralExataService.IdentidadeCadastral> conferir(@RequestBody Documentos dados) {
        return ResponseEntity.ok().cacheControl(noStore()).header("Pragma", "no-cache")
                .body(cadastro.localizar(dados.cpf(), dados.cns()));
    }

    @GetMapping({"/pacientes", "/pacientes/pesquisa"})
    ConsultasClinicasLocaisEnsaioService.Pagina pesquisar(@RequestParam(required = false) String q,
            @RequestParam(required = false) ClassificacaoRisco classificacao, @RequestParam(required = false) StatusPaciente status,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return pacientes.pesquisar(q, classificacao, status, tipoAcompanhamento, page, size);
    }

    @GetMapping({"/pacientes/{paciente}", "/pacientes/{paciente}/detalhe"})
    ConsultasClinicasLocaisEnsaioService.Detalhe detalhe(@PathVariable UUID paciente) {
        return pacientes.detalhe(paciente);
    }

    @GetMapping({"/pacientes/{paciente}/prontuario", "/pacientes/{paciente}/historico"})
    ConsultasClinicasLocaisEnsaioService.Prontuario prontuario(@PathVariable UUID paciente) {
        return pacientes.prontuario(paciente);
    }

    @GetMapping("/pacientes/{paciente}/agendamentos")
    List<AgendamentoDTO> agendamentos(@PathVariable UUID paciente) {
        return pacientes.prontuario(paciente).atendimentos();
    }

    @GetMapping("/agendamentos/{id}")
    AgendamentoDTO agendamento(@PathVariable Long id) {
        return fatos.atendimento(id);
    }

    @GetMapping("/agendamentos/agenda/{data}")
    List<AgendamentoDTO> dia(@PathVariable LocalDate data, @RequestParam(required = false) UUID profissionalId) {
        return fatos.agenda(data, data, profissionalId);
    }

    @GetMapping("/agendamentos/agenda")
    List<AgendamentoDTO> agenda(@RequestParam LocalDate dataInicio, @RequestParam LocalDate dataFim,
            @RequestParam(required = false) UUID profissionalId) {
        return fatos.agenda(dataInicio, dataFim, profissionalId);
    }

    @GetMapping("/agendamentos/vagas")
    Map<TurnoEnum, Integer> vagas(@RequestParam UUID usuarioId, @RequestParam LocalDate data) {
        return fatos.vagas(usuarioId, data);
    }

    @GetMapping("/agendamentos/horarios-disponiveis")
    HorariosDisponiveisDTO horarios(@RequestParam UUID usuarioId, @RequestParam LocalDate data, @RequestParam TurnoEnum turno) {
        return fatos.horarios(usuarioId, data, turno);
    }

    @GetMapping("/agendamentos/{id}/remarcacao")
    List<LocalDate> sugerir(@PathVariable Long id) {
        return fatos.datasRemarcacao(id);
    }

    @GetMapping("/grupos")
    List<GrupoTerapeuticoDTO> grupos() {
        return fatos.grupos();
    }

    @GetMapping("/grupos/{grupo}")
    GrupoTerapeuticoDTO grupo(@PathVariable Long grupo) {
        return fatos.grupo(grupo);
    }

    @GetMapping("/grupos/{grupo}/proxima-data-sugerida")
    LocalDate proxima(@PathVariable Long grupo) {
        return fatos.proximaData(grupo);
    }

    @GetMapping("/grupos/sessoes")
    List<SessaoGrupoDTO> sessoes(@RequestParam LocalDate dataInicio, @RequestParam LocalDate dataFim) {
        return fatos.sessoes(dataInicio, dataFim);
    }

    @GetMapping("/grupos/{grupo}/participantes")
    List<ParticipanteGrupoDTO> participantes(@PathVariable Long grupo) {
        return fatos.participantesGrupo(grupo);
    }

    @GetMapping("/grupos/{grupo}/sessoes-para-inscricao-retroativa")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
    List<SessaoInscricaoRetroativaDTO> retroativas(@PathVariable Long grupo) {
        return fatos.retroativas(grupo);
    }

    @GetMapping("/pacientes/busca-ativa")
    BuscaAtivaPainelLocalEnsaioService.Busca busca(@RequestParam UUID requisicao) {
        return auditoria.consultarLista(requisicao);
    }

    record ResumoInicio(long totalPacientesAtivos, long totalAgendamentosHoje, Long totalFaltasUltimosSeteDias,
            long totalPacientesAtencao, Long totalNotificacoes,
            List<BuscaAtivaPainelLocalEnsaioService.Prioritario> pacientesPrioritarios) {}

    @GetMapping("/inicio/resumo")
    ResumoInicio inicio() {
        var resumo = painel.painel(1, null, null, null);
        var hoje = LocalDate.now(clock);
        var agenda = fatos.agenda(hoje.minusDays(6), hoje, null);
        long agendados = agenda.stream().filter(a -> hoje.equals(a.dataAgendamento())
                && a.situacaoAtendimento() == SituacaoAtendimento.AGENDADO).count();
        long faltasComprovadas = agenda.stream().filter(a -> !Boolean.TRUE.equals(a.migracaoPendente())
                && a.frequenciaAssistencial() == FrequenciaAssistencial.FALTOU).count();
        var distribuicao = resumo.distribuicaoClassificacao();
        return new ResumoInicio(resumo.totalPacientesAtivos(), agendados, faltasComprovadas,
                distribuicao.amarelos() + distribuicao.vermelhos(), null, resumo.pacientesPrioritarios());
    }

    @GetMapping("/painel/resumo")
    BuscaAtivaPainelLocalEnsaioService.Painel painel(@RequestParam(defaultValue = "6") int periodoMeses,
            @RequestParam(required = false) String unidade, @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento,
            @RequestParam(required = false) Boolean situacaoRua) {
        return painel.painel(periodoMeses, unidade, tipoAcompanhamento, situacaoRua);
    }

    @PostMapping("/pacientes/{paciente}/vinculos/{vinculo}/busca-ativa/visualizacao")
    AuditoriaBuscaLocalEnsaioService.Resultado visualizar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
            @RequestBody Auditoria dados) {
        return auditoria.visualizar(paciente, vinculo, dados.version(), dados.requisicao());
    }

    @PostMapping("/pacientes/{paciente}/vinculos/{vinculo}/busca-ativa/encaminhamento")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
    AuditoriaBuscaLocalEnsaioService.Resultado encaminhar(@PathVariable UUID paciente, @PathVariable UUID vinculo,
            @RequestBody Auditoria dados) {
        return auditoria.encaminhar(paciente, vinculo, dados.version(), dados.requisicao(), dados.destino(), dados.descricao());
    }

    record PaginaAuditoria(List<AuditoriaBuscaAtivaDTO> content, long totalElements, int totalPages) {}

    @GetMapping("/auditoria/busca-ativa")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    PaginaAuditoria auditoria(@RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamanho) {
        var dados = auditoria.listarUnidade(pagina, tamanho);
        return new PaginaAuditoria(dados.getContent(), dados.getTotalElements(), dados.getTotalPages());
    }

    @GetMapping("/relatorios/pendencias")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    PainelPendenciasLocalEnsaioService.Pagina pendencias(
            @RequestParam(required = false) PainelPendenciasLocalEnsaioService.Estado estado,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamanho) {
        return pendencias.listar(estado, pagina, tamanho);
    }

    @GetMapping("/relatorios/busca-ativa")
    RelatoriosLocaisEnsaioService.Busca relatorioBusca(@RequestParam(required = false) LocalDate dataInicio,
            @RequestParam(required = false) LocalDate dataFim, @RequestParam(required = false) UUID profissionalId,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento) {
        return relatorios.buscaAtiva(dataInicio, dataFim, profissionalId, tipoAcompanhamento);
    }

    @GetMapping("/relatorios/frequencia-grupos")
    RelatoriosLocaisEnsaioService.Grupos relatorioGrupos(@RequestParam(required = false) LocalDate dataInicio,
            @RequestParam(required = false) LocalDate dataFim, @RequestParam(required = false) Long grupoId) {
        return relatorios.frequenciaGrupos(dataInicio, dataFim, grupoId);
    }

    @GetMapping("/relatorios/{tipo:busca-ativa|frequencia-grupos}/{formato:pdf|excel}")
    ResponseEntity<byte[]> exportar(@PathVariable String tipo, @PathVariable String formato,
            @RequestParam(required = false) LocalDate dataInicio, @RequestParam(required = false) LocalDate dataFim,
            @RequestParam(required = false) UUID profissionalId,
            @RequestParam(required = false) TipoAcompanhamento tipoAcompanhamento,
            @RequestParam(required = false) Long grupoId) {
        boolean excel = formato.equals("excel");
        byte[] bytes = tipo.equals("busca-ativa")
                ? relatorios.exportarBusca(dataInicio, dataFim, profissionalId, tipoAcompanhamento, excel)
                : relatorios.exportarGrupos(dataInicio, dataFim, grupoId, excel);
        return ResponseEntity.ok().cacheControl(noStore()).contentType(excel
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.APPLICATION_PDF).body(bytes);
    }
}

package com.pet.buscaativa.services.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.dto.BuscaAtivaRelatorioDTO;
import com.pet.buscaativa.entities.dto.FrequenciaGrupoRelatorioDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.GrupoTerapeuticoRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.RelatorioService;
import com.pet.buscaativa.services.PdfRendererService;
import com.pet.buscaativa.services.ExcelRelatorioService;
import com.pet.buscaativa.services.exceptions.ValidationException;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class RelatorioServiceImpl implements RelatorioService {
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final String NAO_INFORMADO = "Não informado";

    private final PacienteRepository pacienteRepository;
    private final SessaoGrupoRepository sessaoGrupoRepository;
    private final UsuarioRepository usuarioRepository;
    private final Clock clock;
    private final PdfRendererService pdfRendererService;
    private final ExcelRelatorioService excelRelatorioService;
    private final EscopoUnidadeService escopoUnidadeService;
    private final GrupoTerapeuticoRepository grupoRepository;

    @Override
    @Transactional(readOnly = true)
    public byte[] gerarBuscaAtiva(LocalDate dataInicio, LocalDate dataFim, UUID profissionalId,
                                  TipoAcompanhamento tipoAcompanhamento) {
        BuscaAtivaPreparada relatorio = prepararBuscaAtiva(dataInicio, dataFim, profissionalId, tipoAcompanhamento);
        return pdfRendererService.renderizar("relatorio-busca-ativa", relatorio.parametros());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] gerarFrequenciaGrupos(LocalDate dataInicio, LocalDate dataFim, Long grupoId) {
        FrequenciaPreparada relatorio = prepararFrequencia(dataInicio, dataFim, grupoId);
        return pdfRendererService.renderizar("relatorio-frequencia-grupos", relatorio.parametros());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] gerarBuscaAtivaExcel(LocalDate dataInicio, LocalDate dataFim, UUID profissionalId,
                                       TipoAcompanhamento tipoAcompanhamento) {
        BuscaAtivaPreparada relatorio = prepararBuscaAtiva(dataInicio, dataFim, profissionalId, tipoAcompanhamento);
        return excelRelatorioService.gerarBuscaAtiva(relatorio.dados(), (String) relatorio.parametros().get("PERIODO"),
                (String) relatorio.parametros().get("PROFISSIONAL"),
                (String) relatorio.parametros().get("TIPO_ACOMPANHAMENTO"));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] gerarFrequenciaGruposExcel(LocalDate dataInicio, LocalDate dataFim, Long grupoId) {
        FrequenciaPreparada relatorio = prepararFrequencia(dataInicio, dataFim, grupoId);
        return excelRelatorioService.gerarFrequenciaGrupos(relatorio.dados(),
                (String) relatorio.parametros().get("PERIODO"), relatorio.media(), relatorio.grupos());
    }

    private BuscaAtivaPreparada prepararBuscaAtiva(LocalDate dataInicio, LocalDate dataFim, UUID profissionalId,
                                                    TipoAcompanhamento tipoAcompanhamento) {
                                                        
        validarPeriodo(dataInicio, dataFim);
        // Os parâmetros de exportação apenas refinam o escopo autenticado.
        escopoUnidadeService.validarUnidadeConfigurada();
        if (profissionalId != null) {
            escopoUnidadeService.validarUsuario(usuarioRepository.findByIdPublico(profissionalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado.")));
        }
        List<BuscaAtivaRelatorioDTO> dados = pacienteRepository.findParaRelatorioBuscaAtiva(
                        StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO, dataInicio, dataFim,
                        profissionalId, tipoAcompanhamento)
                .stream().filter(escopoUnidadeService::pacientePertenceAoEscopo).map(this::paraBuscaAtiva).toList();

        Map<String, Object> parametros = parametrosComuns(dataInicio, dataFim);
        parametros.put("PROFISSIONAL", profissionalId == null ? "Todos os profissionais" : usuarioRepository
                .findByIdPublico(profissionalId).map(u -> u.getNome()).orElse(NAO_INFORMADO));
        parametros.put("TIPO_ACOMPANHAMENTO", descricaoFiltro(tipoAcompanhamento));
        parametros.put("TOTAL", dados.size());
        parametros.put("pacientes", dados);
        return new BuscaAtivaPreparada(dados, parametros);
    }

    private FrequenciaPreparada prepararFrequencia(LocalDate dataInicio, LocalDate dataFim, Long grupoId) {
        LocalDate fim = dataFim == null ? LocalDate.now(clock) : dataFim;
        LocalDate inicio = dataInicio == null ? fim.minusMonths(6) : dataInicio;
        validarPeriodo(inicio, fim);
        escopoUnidadeService.validarUnidadeConfigurada();
        if (grupoId != null) {
            escopoUnidadeService.validarGrupo(grupoRepository.findById(grupoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Grupo não encontrado.")));
        }

        List<SessaoGrupo> sessoes = sessaoGrupoRepository
                .findRealizadasParaRelatorio(inicio, fim, StatusSessaoGrupo.REALIZADA, grupoId).stream()
                .filter(s -> escopoUnidadeService.grupoPertenceAoEscopo(s.getGrupo())).toList();

        List<FrequenciaGrupoRelatorioDTO> dados = sessoes.stream().map(this::paraFrequencia).toList();
        int presentes = sessoes.stream().mapToInt(s -> contarPresencas(s, StatusPresencaGrupo.PRESENTE)).sum();
        int ausentes = sessoes.stream().mapToInt(s -> contarPresencas(s, StatusPresencaGrupo.FALTOU)).sum();

        BigDecimal media = calcularTaxa(presentes, ausentes);
        long grupos = dados.stream().map(FrequenciaGrupoRelatorioDTO::getGrupo).distinct().count();
        Map<String, Object> parametros = parametrosComuns(inicio, fim);
        parametros.put("SESSOES", dados.size());
        parametros.put("PRESENCA_MEDIA", percentual(media));
        parametros.put("GRUPOS", grupos);
        parametros.put("sessoes", dados);
        return new FrequenciaPreparada(dados, parametros, media, grupos);
    }

    BuscaAtivaRelatorioDTO paraBuscaAtiva(Paciente paciente) {
        String documento = texto(paciente.getCns());
        if (NAO_INFORMADO.equals(documento)) documento = texto(paciente.getCpf());
        String profissional = paciente.getProfissionalReferencia() == null
                ? NAO_INFORMADO : texto(paciente.getProfissionalReferencia().getNome());
        return new BuscaAtivaRelatorioDTO(texto(paciente.getNome()), documento,
                paciente.getDataUltimaPresenca(), paciente.getCountFaltas(),
                descricaoTipo(paciente.getTipoAcompanhamento()), profissional, texto(paciente.getTelefone()));
    }

    FrequenciaGrupoRelatorioDTO paraFrequencia(SessaoGrupo sessao) {
        List<String> presentes = nomes(sessao, StatusPresencaGrupo.PRESENTE);
        List<String> ausentes = nomes(sessao, StatusPresencaGrupo.FALTOU);
        BigDecimal taxa = calcularTaxa(presentes.size(), ausentes.size());
        
        return new FrequenciaGrupoRelatorioDTO(sessao.getDataSessao(), texto(sessao.getGrupo().getTema()),
                texto(sessao.getGrupo().getCoordenador().getNome()), presentes.size(), ausentes.size(),
                presentes, ausentes, taxa, percentual(taxa));
    }

    private int contarPresencas(SessaoGrupo sessao, StatusPresencaGrupo status) {
        return (int) sessao.getParticipantes().stream()
                .filter(participante -> participante.getStatusPresenca() == status)
                .filter(participante -> escopoUnidadeService.pacientePertenceAoEscopo(participante.getPaciente()))
                .count();
    }

    private List<String> nomes(SessaoGrupo sessao, StatusPresencaGrupo status) {
        return sessao.getParticipantes().stream().filter(p -> p.getStatusPresenca() == status)
                .filter(p -> escopoUnidadeService.pacientePertenceAoEscopo(p.getPaciente()))
                .map(p -> texto(p.getPaciente().getNome())).sorted().toList();
    }

    private BigDecimal calcularTaxa(int presentes, int ausentes) {
        int total = presentes + ausentes;
        return total == 0 ? BigDecimal.ZERO.setScale(1) : BigDecimal.valueOf(presentes)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private String percentual(BigDecimal valor) {
        return String.format(PT_BR, "%.1f%%", valor);
    }

    private Map<String, Object> parametrosComuns(LocalDate inicio, LocalDate fim) {
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("PERIODO", periodo(inicio, fim));
        parametros.put("GERADO_EM", LocalDateTime.now(clock).format(DATA_HORA));
        return parametros;
    }

    

    private void validarPeriodo(LocalDate inicio, LocalDate fim) {
        if (inicio != null && fim != null && inicio.isAfter(fim)) {
            throw new ValidationException("A data inicial deve ser anterior ou igual à data final.");
        }
    }

    private String periodo(LocalDate inicio, LocalDate fim) {
        if (inicio == null && fim == null) return "Todo o período";
        return (inicio == null ? "Início" : inicio.format(DATA)) + " a " +
                (fim == null ? "Hoje" : fim.format(DATA));
    }

    private String descricaoFiltro(TipoAcompanhamento tipo) {
        return tipo == null ? "Individual e grupo" : descricaoTipo(tipo);
    }

    private String descricaoTipo(TipoAcompanhamento tipo) {
        if (tipo == null) return NAO_INFORMADO;
        return switch (tipo) {
            case INDIVIDUAL -> "Individual";
            case GRUPO_TERAPEUTICO -> "Grupo terapêutico";
            case AMBOS -> "Individual e grupo";
        };
    }

    private String texto(String valor) {
        return valor == null || valor.isBlank() ? NAO_INFORMADO : valor.trim();
    }

    private record BuscaAtivaPreparada(List<BuscaAtivaRelatorioDTO> dados, Map<String, Object> parametros) {}
    private record FrequenciaPreparada(List<FrequenciaGrupoRelatorioDTO> dados, Map<String, Object> parametros,
                                       BigDecimal media, long grupos) {}
}

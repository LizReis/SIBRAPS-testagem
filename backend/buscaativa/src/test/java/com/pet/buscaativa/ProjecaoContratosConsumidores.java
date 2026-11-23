package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.test.util.ReflectionTestUtils;

import com.pet.buscaativa.alertas.PacienteVermelhoDTO;
import com.pet.buscaativa.alertas.RelatorioEmailBuilder;
import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.AlertaBuscaAtivaDTO;
import com.pet.buscaativa.entities.dto.BuscaAtivaRelatorioDTO;
import com.pet.buscaativa.entities.dto.FrequenciaGrupoRelatorioDTO;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.PacientePainelDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.services.ExcelRelatorioService;
import com.pet.buscaativa.services.PdfRendererService;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Motivo;
import com.pet.buscaativa.services.impl.PacienteServiceImpl;
import com.pet.buscaativa.services.impl.PainelServiceImpl;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Projecao;

/** Adaptadores de contrato sobre uma única leitura por vínculo e rótulos de fixture. */
final class ProjecaoContratosConsumidores {
    private static final String USF = "USFFixture";
    private static final String AD = "CAPSFixture";
    private static final String GRUPO_USF = "GrupoUSFFixture";
    private static final String GRUPO_CAPS = "GrupoCAPSFixture";
    private static final String PERIODO = "Período da fixture";

    private ProjecaoContratosConsumidores() {}

    static void verificar(Projecao a, Projecao b, Projecao ad, Projecao ii, Projecao pendente,
            PdfRendererService pdf, ExcelRelatorioService excel, RelatorioEmailBuilder email) throws Exception {
        verificarApresentacaoEPainel(a, b, ad, ii, pendente);

        var linhaUsf = buscaAtiva(a, USF);
        var linhaAd = buscaAtiva(ad, AD);
        assertEquals(5, linhaUsf.getFaltasConsecutivas());
        assertEquals(2, linhaAd.getFaltasConsecutivas());
        assertEquals(a.ultimaPresenca().valor().orElseThrow(), linhaUsf.getDataUltimoAtendimento());

        verificarBuscaExportada(pdf, excel, linhaUsf, USF, AD);
        verificarBuscaExportada(pdf, excel, linhaAd, AD, USF);
        verificarGrupoExportado(pdf, excel, a, GRUPO_USF, GRUPO_CAPS, 2);
        verificarGrupoExportado(pdf, excel, ii, GRUPO_CAPS, GRUPO_USF, 1);
        assertTrue(ad.grupos().stream().noneMatch(p -> p.frequencia().isPresent()));
        assertTrue(b.grupos().isEmpty());

        // O provedor de e-mail seleciona ATIVO/VERMELHO. O CAPS AD amarelo continua elegível no PDF/Excel.
        assertEquals(ClassificacaoRisco.VERMELHO, a.risco().valor().orElseThrow());
        assertEquals(ClassificacaoRisco.AMARELO, ad.risco().valor().orElseThrow());
        var html = email.montarCorpoHtml("Equipe sintética", a.dataReferencia(),
                List.of(new PacienteVermelhoDTO(USF, linhaUsf.getFaltasConsecutivas(), linhaUsf.getDataUltimoAtendimento())));
        assertTrue(html.contains(USF));
        assertTrue(html.contains(">5</td>"));
        assertTrue(html.contains(linhaUsf.getDataUltimoAtendimentoFormatada()));
        assertFalse(html.contains(AD));
    }

    private static void verificarApresentacaoEPainel(Projecao a, Projecao b, Projecao ad, Projecao ii, Projecao pendente) {
        var legado = mock(PacienteServiceImpl.class, CALLS_REAL_METHODS);
        var pacienteAd = entradaDoConsumidor(ad);
        var endereco = new Endereco();
        endereco.setLogradouro("Rua sintética");
        endereco.setComplemento("Ponto sintético");
        pacienteAd.setEndereco(endereco);
        var domiciliar = apresentar(legado, pacienteAd);
        assertEquals("Visita domiciliar necessária", domiciliar.alerta());
        assertEquals("Rua sintética", domiciliar.localBusca());

        pacienteAd.setSituacaoRua(true);
        var comReferencia = apresentar(legado, pacienteAd);
        assertEquals("Busca em ponto de referência necessária", comReferencia.alerta());
        assertEquals("Ponto sintético", comReferencia.localBusca());

        endereco.setComplemento(null);
        var semReferencia = apresentar(legado, pacienteAd);
        assertNull(semReferencia.localBusca());
        assertEquals("Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.",
                semReferencia.avisoAdicional());

        var pacientePendente = entradaDoConsumidor(pendente);
        pacientePendente.setEndereco(endereco);
        var revisao = apresentar(legado, pacientePendente);
        assertEquals("Revisão do histórico necessária", revisao.alerta());
        assertNull(revisao.localBusca());

        var painel = mock(PainelServiceImpl.class, CALLS_REAL_METHODS);
        PacientePainelDTO resumoUsf = ReflectionTestUtils.invokeMethod(painel, "paraPrioritario", entradaDoConsumidor(a));
        assertNotNull(resumoUsf);
        assertEquals(ClassificacaoRisco.VERMELHO, resumoUsf.classificacaoRisco());
        assertEquals(5, resumoUsf.quantidadeFaltas());
        PacientePainelDTO resumoAD = ReflectionTestUtils.invokeMethod(painel, "paraPrioritario", entradaDoConsumidor(ad));
        assertNotNull(resumoAD);
        assertEquals(2, resumoAD.quantidadeFaltas());
        assertEquals(Motivo.DADOS_INCOMPLETOS, b.buscaAtiva().valor().orElseThrow().motivo());
        assertEquals(Motivo.ACOMPANHAMENTO_NAO_ATIVO, ii.buscaAtiva().valor().orElseThrow().motivo());
    }

    private static Paciente entradaDoConsumidor(Projecao projecao) {
        var sintetico = new Paciente();
        projecao.status().valor().ifPresent(sintetico::setStatusPaciente);
        projecao.acompanhamento().valor().ifPresent(sintetico::setTipoAcompanhamento);
        projecao.faltas().valor().ifPresent(sintetico::setCountFaltas);
        projecao.ultimaPresenca().valor().ifPresent(sintetico::setDataUltimaPresenca);
        projecao.risco().valor().ifPresent(sintetico::setClassificacaoRisco);
        projecao.pendencias().valor().ifPresent(sintetico::setIndicadoresPendentes);
        projecao.visita().valor().ifPresent(sintetico::setGatilhoVisitaAcionado);
        return sintetico;
    }

    private static AlertaBuscaAtivaDTO apresentar(PacienteServiceImpl legado, Paciente sintetico) {
        return ReflectionTestUtils.invokeMethod(legado, "montarAlertaBuscaAtiva", sintetico);
    }

    private static BuscaAtivaRelatorioDTO buscaAtiva(Projecao p, String rotulo) {
        assertTrue(p.risco().valor().orElseThrow() == ClassificacaoRisco.VERMELHO || p.visita().valor().orElseThrow());
        return new BuscaAtivaRelatorioDTO(rotulo, "Documento sintético", p.ultimaPresenca().valor().orElse(null),
                p.faltas().valor().orElseThrow(), p.acompanhamento().valor().orElseThrow().name(),
                "Profissional sintético", "Contato sintético");
    }

    private static void verificarBuscaExportada(PdfRendererService pdf, ExcelRelatorioService excel,
            BuscaAtivaRelatorioDTO linha, String esperado, String externo) throws Exception {
        var dados = List.of(linha);
        var texto = textoPdf(pdf.renderizar("relatorio-busca-ativa", Map.of(
                "PERIODO", PERIODO, "PROFISSIONAL", "Equipe sintética", "TIPO_ACOMPANHAMENTO", "Ambos",
                "TOTAL", dados.size(), "pacientes", dados, "GERADO_EM", PERIODO)));
        assertTrue(texto.contains(esperado), texto);
        assertTrue(texto.contains(linha.getDataUltimoAtendimentoFormatada()), texto);
        assertFalse(texto.contains(externo));

        byte[] bytes = excel.gerarBuscaAtiva(dados, PERIODO, "Equipe sintética", "Ambos");
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = livro.getSheet("Busca Ativa");
            var registro = sheet.getRow(8);
            assertEquals(esperado, registro.getCell(0).getStringCellValue());
            assertEquals(linha.getFaltasConsecutivas().doubleValue(), registro.getCell(3).getNumericCellValue());
            assertFalse(textoPlanilha(sheet).contains(externo));
        }
    }

    private static void verificarGrupoExportado(PdfRendererService pdf, ExcelRelatorioService excel,
            Projecao p, String esperado, String externo, int quantidade) throws Exception {
        var dados = p.grupos().stream().filter(g -> g.frequencia().isPresent()).map(g -> {
            boolean presente = g.frequencia().orElseThrow() == StatusPresencaGrupo.PRESENTE;
            return new FrequenciaGrupoRelatorioDTO(g.data(), esperado, "Responsável sintético",
                    presente ? 1 : 0, presente ? 0 : 1, List.of(), List.of(),
                    presente ? BigDecimal.valueOf(100) : BigDecimal.ZERO, presente ? "100%" : "0%");
        }).toList();
        assertEquals(quantidade, dados.size());
        var texto = textoPdf(pdf.renderizar("relatorio-frequencia-grupos", Map.of(
                "PERIODO", PERIODO, "PRESENCA_MEDIA", "0%", "GRUPOS", 1,
                "SESSOES", dados.size(), "sessoes", dados, "GERADO_EM", PERIODO)));
        assertTrue(texto.contains(esperado), texto);
        assertFalse(texto.contains(externo));
        try (var livro = new XSSFWorkbook(new ByteArrayInputStream(
                excel.gerarFrequenciaGrupos(dados, PERIODO, BigDecimal.ZERO, 1)))) {
            var sheet = livro.getSheet("Frequência de Grupos");
            for (int i = 0; i < dados.size(); i++) {
                var linha = sheet.getRow(8 + i);
                assertEquals(esperado, linha.getCell(1).getStringCellValue());
                assertEquals(0, linha.getCell(3).getNumericCellValue());
                assertEquals(1, linha.getCell(4).getNumericCellValue());
            }
            assertFalse(textoPlanilha(sheet).contains(externo));
        }
    }

    private static String textoPlanilha(Sheet sheet) {
        var formato = new DataFormatter();
        var texto = new StringBuilder();
        sheet.forEach(linha -> linha.forEach(celula -> texto.append(formato.formatCellValue(celula)).append('\n')));
        return texto.toString();
    }

    private static String textoPdf(byte[] bytes) throws Exception {
        try (var doc = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}

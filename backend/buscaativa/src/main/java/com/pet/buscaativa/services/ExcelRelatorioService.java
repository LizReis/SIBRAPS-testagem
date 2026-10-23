package com.pet.buscaativa.services;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.dto.BuscaAtivaRelatorioDTO;
import com.pet.buscaativa.entities.dto.FrequenciaGrupoRelatorioDTO;
import com.pet.buscaativa.services.exceptions.RelatorioException;

@Service
public class ExcelRelatorioService {
    private static final String SEM_REGISTROS = "Nenhum registro encontrado para os filtros selecionados.";

    public byte[] gerarBuscaAtiva(List<BuscaAtivaRelatorioDTO> dados, String periodo, String profissional,
                                  String tipoAcompanhamento) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Busca Ativa");
            Estilos estilos = new Estilos(workbook);
            criarTitulo(sheet, estilos, "Relatório de Busca Ativa", 7);
            criarInformacao(sheet, estilos, 2, "Período", periodo);
            criarInformacao(sheet, estilos, 3, "Profissional", profissional);
            criarInformacao(sheet, estilos, 4, "Tipo de acompanhamento", tipoAcompanhamento);
            criarInformacao(sheet, estilos, 5, "Total de pacientes", dados.size());

            int cabecalho = 7;
            String[] colunas = { "Nome completo", "CNS/CPF", "Último atendimento", "Faltas consecutivas",
                    "Tipo de acompanhamento", "Profissional de referência", "Contato telefônico" };
            criarCabecalho(sheet, estilos, cabecalho, colunas);
            int linha = cabecalho + 1;
            for (BuscaAtivaRelatorioDTO item : dados) {
                Row row = sheet.createRow(linha++);
                texto(row, 0, item.getNomeCompleto(), estilos.dado);
                texto(row, 1, item.getCnsCpf(), estilos.dado);
                data(row, 2, item.getDataUltimoAtendimento(), estilos.data);
                numero(row, 3, item.getFaltasConsecutivas(), estilos.faltas);
                texto(row, 4, item.getTipoAcompanhamento(), estilos.dado);
                texto(row, 5, item.getProfissionalReferencia(), estilos.dado);
                texto(row, 6, item.getTelefone(), estilos.dado);
            }
            finalizarTabela(sheet, estilos, cabecalho, linha, colunas.length, dados.isEmpty(),
                    new int[] { 28, 20, 18, 18, 24, 28, 20 });
            return bytes(workbook);
        } catch (Exception e) {
            throw new RelatorioException("Não foi possível gerar o relatório de busca ativa em Excel.", e);
        }
    }

    public byte[] gerarFrequenciaGrupos(List<FrequenciaGrupoRelatorioDTO> dados, String periodo,
                                        BigDecimal presencaMedia, long grupos) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Frequência de Grupos");
            Estilos estilos = new Estilos(workbook);
            criarTitulo(sheet, estilos, "Relatório de Frequência de Grupos", 8);
            criarInformacao(sheet, estilos, 2, "Período", periodo);
            criarInformacao(sheet, estilos, 3, "Sessões", dados.size());
            Row media = criarInformacao(sheet, estilos, 4, "Presença média", null);
            Cell mediaCell = media.createCell(1);
            mediaCell.setCellValue(presencaMedia.doubleValue() / 100);
            mediaCell.setCellStyle(estilos.percentual);
            criarInformacao(sheet, estilos, 5, "Grupos acompanhados", grupos);

            int cabecalho = 7;
            String[] colunas = { "Data", "Grupo terapêutico", "Coordenador do grupo", "Presentes", "Ausentes",
                    "Taxa de presença", "Participantes presentes", "Participantes ausentes" };
            criarCabecalho(sheet, estilos, cabecalho, colunas);
            int linha = cabecalho + 1;
            for (FrequenciaGrupoRelatorioDTO item : dados) {
                Row row = sheet.createRow(linha++);
                data(row, 0, item.getData(), estilos.data);
                texto(row, 1, item.getGrupo(), estilos.dado);
                texto(row, 2, item.getCoordenador(), estilos.dado);
                numero(row, 3, item.getQuantidadePresentes(), estilos.numero);
                numero(row, 4, item.getQuantidadeAusentes(), estilos.numero);
                Cell taxa = row.createCell(5);
                double percentual = item.getTaxaPresenca().doubleValue() / 100;
                taxa.setCellValue(percentual);
                taxa.setCellStyle(percentual >= .75 ? estilos.taxaAlta : estilos.taxaBaixa);
                texto(row, 6, String.join(", ", item.getNomesPresentes()), estilos.dado);
                texto(row, 7, String.join(", ", item.getNomesAusentes()), estilos.dado);
            }
            finalizarTabela(sheet, estilos, cabecalho, linha, colunas.length, dados.isEmpty(),
                    new int[] { 16, 28, 28, 13, 13, 18, 45, 45 });
            return bytes(workbook);
        } catch (Exception e) {
            throw new RelatorioException("Não foi possível gerar o relatório de frequência em Excel.", e);
        }
    }

    private void criarTitulo(Sheet sheet, Estilos estilos, String titulo, int colunas) {
        Row marca = sheet.createRow(0);
        marca.createCell(0).setCellValue("SIBRAPS");
        marca.getCell(0).setCellStyle(estilos.titulo);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, colunas - 1));
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(titulo);
        row.getCell(0).setCellStyle(estilos.subtitulo);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, colunas - 1));
    }

    private Row criarInformacao(Sheet sheet, Estilos estilos, int indice, String rotulo, Object valor) {
        Row row = sheet.createRow(indice);
        texto(row, 0, rotulo, estilos.rotulo);
        if (valor instanceof Number numero) {
            Cell cell = row.createCell(1);
            cell.setCellValue(numero.doubleValue());
            cell.setCellStyle(estilos.numero);
        } else if (valor != null) {
            texto(row, 1, valor.toString(), estilos.dado);
        }
        return row;
    }

    private void criarCabecalho(Sheet sheet, Estilos estilos, int indice, String[] colunas) {
        Row row = sheet.createRow(indice);
        row.setHeightInPoints(30);
        for (int i = 0; i < colunas.length; i++) texto(row, i, colunas[i], estilos.cabecalho);
    }

    private void finalizarTabela(Sheet sheet, Estilos estilos, int cabecalho, int proximaLinha, int colunas,
                                 boolean vazio, int[] larguras) {
        int ultimaLinha = proximaLinha - 1;
        if (vazio) {
            Row row = sheet.createRow(proximaLinha);
            texto(row, 0, SEM_REGISTROS, estilos.vazio);
            sheet.addMergedRegion(new CellRangeAddress(proximaLinha, proximaLinha, 0, colunas - 1));
        }
        sheet.createFreezePane(0, cabecalho + 1);
        sheet.setAutoFilter(new CellRangeAddress(cabecalho, Math.max(cabecalho, ultimaLinha), 0, colunas - 1));
        for (int i = 0; i < larguras.length; i++) sheet.setColumnWidth(i, larguras[i] * 256);
    }

    private void texto(Row row, int coluna, String valor, CellStyle estilo) {
        Cell cell = row.createCell(coluna);
        cell.setCellValue(valor == null || valor.isBlank() ? "Não informado" : valor);
        cell.setCellStyle(estilo);
    }

    private void numero(Row row, int coluna, Integer valor, CellStyle estilo) {
        Cell cell = row.createCell(coluna);
        cell.setCellValue(valor == null ? 0 : valor);
        cell.setCellStyle(estilo);
    }

    private void data(Row row, int coluna, java.time.LocalDate valor, CellStyle estilo) {
        Cell cell = row.createCell(coluna);
        if (valor == null) cell.setBlank();
        else cell.setCellValue(valor.atStartOfDay());
        cell.setCellStyle(estilo);
    }

    private byte[] bytes(XSSFWorkbook workbook) throws java.io.IOException {
        try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            workbook.write(saida);
            return saida.toByteArray();
        }
    }

    private static final class Estilos {
        private final CellStyle titulo;
        private final CellStyle subtitulo;
        private final CellStyle rotulo;
        private final CellStyle cabecalho;
        private final CellStyle dado;
        private final CellStyle numero;
        private final CellStyle data;
        private final CellStyle percentual;
        private final CellStyle faltas;
        private final CellStyle taxaAlta;
        private final CellStyle taxaBaixa;
        private final CellStyle vazio;

        private Estilos(XSSFWorkbook workbook) {
            titulo = estilo(workbook, IndexedColors.DARK_BLUE, IndexedColors.WHITE, true);
            workbook.getFontAt(titulo.getFontIndex()).setFontHeightInPoints((short) 20);
            subtitulo = estilo(workbook, IndexedColors.DARK_BLUE, IndexedColors.WHITE, true);
            workbook.getFontAt(subtitulo.getFontIndex()).setFontHeightInPoints((short) 13);
            rotulo = estilo(workbook, IndexedColors.WHITE, IndexedColors.DARK_BLUE, true);
            cabecalho = estilo(workbook, IndexedColors.PALE_BLUE, IndexedColors.DARK_BLUE, true);
            dado = estilo(workbook, IndexedColors.WHITE, IndexedColors.BLACK, false);
            numero = clonar(workbook, dado);
            numero.setAlignment(HorizontalAlignment.CENTER);
            data = clonar(workbook, dado);
            data.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            percentual = clonar(workbook, dado);
            percentual.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            faltas = clonar(workbook, numero);
            faltas.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            faltas.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            workbook.getFontAt(faltas.getFontIndex()).setColor(IndexedColors.DARK_RED.getIndex());
            taxaAlta = clonar(workbook, percentual);
            taxaAlta.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            taxaAlta.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            taxaBaixa = clonar(workbook, percentual);
            taxaBaixa.setFillForegroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            taxaBaixa.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            vazio = clonar(workbook, dado);
            vazio.setAlignment(HorizontalAlignment.CENTER);
        }

        private static CellStyle estilo(XSSFWorkbook workbook, IndexedColors fundo, IndexedColors fonte, boolean negrito) {
            CellStyle estilo = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(negrito);
            font.setColor(fonte.getIndex());
            estilo.setFont(font);
            estilo.setFillForegroundColor(fundo.getIndex());
            estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            estilo.setWrapText(true);
            estilo.setVerticalAlignment(VerticalAlignment.CENTER);
            estilo.setBorderBottom(BorderStyle.THIN);
            estilo.setBorderTop(BorderStyle.THIN);
            estilo.setBorderLeft(BorderStyle.THIN);
            estilo.setBorderRight(BorderStyle.THIN);
            estilo.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            estilo.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            estilo.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            estilo.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            return estilo;
        }

        private static CellStyle clonar(XSSFWorkbook workbook, CellStyle original) {
            CellStyle estilo = workbook.createCellStyle();
            estilo.cloneStyleFrom(original);
            Font fontOriginal = workbook.getFontAt(original.getFontIndex());
            Font font = workbook.createFont();
            font.setBold(fontOriginal.getBold());
            font.setColor(fontOriginal.getColor());
            estilo.setFont(font);
            return estilo;
        }
    }
}
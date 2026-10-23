package com.pet.buscaativa.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.pet.buscaativa.services.exceptions.RelatorioException;

@Service
public class PdfRendererService {
    private static final String CSS = "reports/relatorios-pdf.css";
    private static final String IMAGENS = "reports/images/";

    private final TemplateEngine templateEngine;
    private final String css;

    public PdfRendererService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        this.css = lerTexto(CSS);
    }

    public byte[] renderizar(String template, Map<String, Object> variaveis) {
        try {
            Context contexto = new Context();
            contexto.setVariables(variaveis);
            contexto.setVariable("css", css);

            contexto.setVariable("logo", imagemObrigatoria("logo-sibraps.svg"));
            contexto.setVariable("iconeCalendario", imagemOpcional("calendario.svg"));
            contexto.setVariable("iconeProfissional", imagemOpcional("profissional.svg"));
            contexto.setVariable("iconeGrupo", imagemOpcional("grupo.svg"));
            contexto.setVariable("iconeSessoes", imagemOpcional("sessoes.svg"));
            contexto.setVariable("iconeGrafico", imagemOpcional("grafico.svg"));
            contexto.setVariable("iconeAlerta", imagemOpcional("alerta.svg"));
            contexto.setVariable("iconeDocumento", imagemOpcional("documento.svg"));
            contexto.setVariable("iconeInformacao", imagemOpcional("informacao.svg"));

            String html = templateEngine.process("reports/" + template, contexto);

            try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.useSVGDrawer(new BatikSVGDrawer());
                builder.withHtmlContent(html, null);
                builder.toStream(saida);
                builder.run();

                byte[] pdf = saida.toByteArray();
                if (pdf.length < 4 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                    throw new RelatorioException("O renderizador não produziu um PDF válido.", null);
                }
                return pdf;
            }
        } catch (RelatorioException e) {
            throw e;
        } catch (Exception e) {
            throw new RelatorioException("Não foi possível gerar o relatório em PDF.", e);
        }
    }

    private String imagemObrigatoria(String nome) {
        String imagem = carregarImagem(nome);
        if (imagem == null) {
            throw new RelatorioException("Não foi possível carregar o símbolo SIBRAPS: " + nome, null);
        }
        return imagem;
    }

    private String imagemOpcional(String nome) {
        return carregarImagem(nome);
    }

    private String carregarImagem(String nome) {
        ClassPathResource recurso = new ClassPathResource(IMAGENS + nome);
        if (!recurso.exists()) {
            return null;
        }

        try (InputStream input = recurso.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            String mime = nome.toLowerCase().endsWith(".svg") ? "image/svg+xml" : "image/png";
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new RelatorioException("Não foi possível carregar o recurso visual do relatório: " + nome, e);
        }
    }

    private String lerTexto(String caminho) {
        try (InputStream recurso = new ClassPathResource(caminho).getInputStream()) {
            return new String(recurso.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RelatorioException("Não foi possível carregar o estilo dos relatórios.", e);
        }
    }
}

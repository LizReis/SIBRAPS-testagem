package com.pet.buscaativa.alertas;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RelatorioEmailBuilder {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Value("${app.alertas.url-sistema:}")
    private String urlSistema;

    public String montarAssunto(LocalDate data, int quantidade) {
        String dataTexto = data.format(FORMATO_DATA);

        if (quantidade == 0) {
            return "[Busca Ativa] " + dataTexto + " - Nenhum paciente em risco";
        }
        return "[Busca Ativa] " + dataTexto + " - " + quantidade + " paciente(s) em risco";
    }

    public String montarCorpoHtml(String nomeDestinatario, LocalDate data, List<PacienteVermelhoDTO> pacientes) {
        StringBuilder html = new StringBuilder();

        html.append("<div style=\"font-family:Arial,sans-serif;color:#1e293b;max-width:640px;\">");
        html.append("<h2 style=\"color:#003fa3;\">Resumo diário de pacientes em risco</h2>");
        html.append("<p>Olá, ").append(esc(nomeDestinatario)).append(".</p>");
        html.append("<p>Situação em <strong>").append(data.format(FORMATO_DATA)).append("</strong>.</p>");

        if (pacientes == null || pacientes.isEmpty()) {
            html.append("<p style=\"background:#dcfce7;color:#15803d;padding:12px;border-radius:8px;\">")
                    .append("Nenhum paciente em risco hoje.</p>");
        } else {
            html.append("<p><strong>").append(pacientes.size())
                    .append("</strong> paciente(s) da sua unidade em risco:</p>");

            html.append("<table style=\"border-collapse:collapse;width:100%;font-size:14px;\">");
            html.append("<thead><tr style=\"background:#fee2e2;color:#991b1b;text-align:left;\">");
            html.append(celulaCabecalho("Paciente"));
            html.append(celulaCabecalho("Faltas"));
            html.append(celulaCabecalho("Última presença"));
            html.append("</tr></thead><tbody>");

            for (PacienteVermelhoDTO p : pacientes) {
                html.append("<tr>");
                html.append(celula(esc(p.nome())));
                html.append(celula(p.countFaltas() == null ? "0" : String.valueOf(p.countFaltas())));
                html.append(celula(p.dataUltimaPresenca() == null
                        ? "Sem registro"
                        : p.dataUltimaPresenca().format(FORMATO_DATA)));
                html.append("</tr>");
            }

            html.append("</tbody></table>");
        }

        if (urlSistema != null && !urlSistema.isBlank()) {
            html.append("<p style=\"margin-top:20px;\">Os detalhes estão no sistema: ")
                    .append("<a href=\"").append(esc(urlSistema)).append("\">acessar Busca Ativa</a>.</p>");
        }

        html.append("<p style=\"color:#64748b;font-size:12px;margin-top:24px;\">")
                .append("E-mail automático do sistema Busca Ativa. Contém informações restritas: ")
                .append("não encaminhe a terceiros.</p>");
        html.append("</div>");

        return html.toString();
    }

    private String celulaCabecalho(String texto) {
        return "<th style=\"padding:8px;border-bottom:2px solid #fecaca;\">" + texto + "</th>";
    }

    private String celula(String texto) {
        return "<td style=\"padding:8px;border-bottom:1px solid #e2e8f0;\">" + texto + "</td>";
    }

    private String esc(String valor) {
        if (valor == null || valor.isBlank()) {
            return "-";
        }
        return valor
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
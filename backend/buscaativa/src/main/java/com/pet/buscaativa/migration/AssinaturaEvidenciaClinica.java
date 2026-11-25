package com.pet.buscaativa.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** Projeções versionadas do estado necessário à reconstrução histórica. Não inclui documentos ou nomes. */
public final class AssinaturaEvidenciaClinica {
    private AssinaturaEvidenciaClinica() {}

    public static final Map<String, List<String>> FONTES;
    static {
        var fontes = new TreeMap<String, List<String>>();
        fontes.put("TB_VINCULO_ASSISTENCIAL", List.of("ID", "ID_PUBLICO", "PACIENTE_ID", "UNIDADE_ATUACAO", "USF_ATUACAO_ID", "UNIDADE_CHAVE", "STATUS_PACIENTE", "TIPO_ACOMPANHAMENTO", "COUNT_FALTAS", "DATA_ULTIMA_PRESENCA", "CLASSIFICACAO_RISCO", "GATILHO_VISITA", "INDICADORES_PENDENTES", "PENDENCIA_EMPATE_LOCAL"));
        fontes.put("TB_PACIENTE", List.of("ID", "SITUACAO_RUA"));
        fontes.put("TB_AGENDAMENTO", List.of("ID", "PACIENTE_ID", "USUARIO_ID", "UNIDADE_ORIGEM_CHAVE", "AGENDAMENTO_ORIGINAL_ID", "DATA_AGENDAMENTO", "HORA_ATENDIMENTO", "REMARCACAO_APOS_FALTA", "SITUACAO_RESERVA", "FREQUENCIA_ASSISTENCIAL", "MIGRACAO_PENDENTE"));
        fontes.put("TB_GRUPO_TERAPEUTICO", List.of("ID", "UNIDADE_ORIGEM_CHAVE"));
        fontes.put("TB_SESSAO_GRUPO", List.of("ID", "GRUPO_ID", "UNIDADE_ORIGEM_CHAVE", "DATA_SESSAO", "HORARIO", "STATUS", "RESPONSAVEL_ATENDIMENTO_ID", "RESPONSABILIDADE_PENDENTE", "MOTIVO_RESPONSABILIDADE_PENDENTE", "ORIGEM_RESPONSABILIDADE"));
        fontes.put("TB_SESSAO_GRUPO_PARTICIPANTE", List.of("ID", "PACIENTE_ID", "SESSAO_GRUPO_ID", "STATUS_PRESENCA"));
        fontes.put("TB_EVENTO_ESTADO_CLINICO_VINCULO", List.of("ID", "VINCULO_ID", "UNIDADE_CHAVE", "ATOR_ID", "OCORRIDO_EM", "NATUREZA", "STATUS_ANTERIOR", "STATUS_RESULTANTE", "MOTIVO", "DESCRICAO"));
        FONTES = Collections.unmodifiableMap(fontes);
    }

    /** Enquadramento por tamanho e distinção de null; nenhuma concatenação ambígua. */
    public static String calcular(String tabela, Object[] valores) throws SQLException {
        var colunas = FONTES.get(tabela);
        if (colunas == null || colunas.size() != valores.length)
            throw new SQLException("Projeção de evidência clínica inválida.");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            adicionar(digest, "SIBRAPS-COBERTURA-V21");
            adicionar(digest, tabela);
            for (int i = 0; i < valores.length; i++) {
                adicionar(digest, colunas.get(i));
                adicionar(digest, canonico(valores[i]));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new SQLException("SHA-256 indisponível.", e);
        }
    }

    private static String canonico(Object valor) {
        if (valor == null) return null;
        if (valor instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (valor instanceof java.sql.Time t) return t.toLocalTime().toString();
        if (valor instanceof java.sql.Timestamp t) return t.toLocalDateTime().toString();
        return valor.toString();
    }

    private static void adicionar(MessageDigest digest, String valor) {
        if (valor == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array());
            return;
        }
        byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

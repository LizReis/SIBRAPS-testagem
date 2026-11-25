package com.pet.buscaativa.migration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Diagnóstico offline, somente contagens. Mesma extração ASCII de DocumentoUtil.
 * Nunca corrigir, mesclar ou apagar identidades nem registrar documentos em logs. */
public final class IdentidadeDocumentalPreflight {
    private IdentidadeDocumentalPreflight() {}

    public record Relatorio(long total, long cpfsDuplicados, long cnsDuplicados,
            long cpfsInconclusivos, long cnsInconclusivos, long semCpf, long semCns,
            long cpfsFormatados, long cnsFormatados) {
        public boolean bloqueiaV17() {
            return cpfsDuplicados > 0 || cnsDuplicados > 0 || cpfsInconclusivos > 0 || cnsInconclusivos > 0;
        }
    }

    public static Relatorio analisar(DataSource source) {
        var jdbc = new JdbcTemplate(source);
        return new Relatorio(contar(jdbc,"select count(*) from tb_paciente"),
                duplicados(jdbc,"cpf"),duplicados(jdbc,"cns"),
                inconclusivos(jdbc,"cpf",11),
                inconclusivos(jdbc,"cns",15),
                ausentes(jdbc,"cpf"),ausentes(jdbc,"cns"),
                formatados(jdbc,"cpf"),formatados(jdbc,"cns"));
    }

    private static String normalizado(String coluna) {
        return "nullif(regexp_replace("+coluna+", '[^0-9]', ''), '')";
    }
    private static long duplicados(JdbcTemplate j,String c) {
        String n=normalizado(c);
        return contar(j,"select count(*) from (select "+n+" doc from tb_paciente where "+n+" is not null group by "+n+" having count(*)>1) repetidos");
    }
    private static long ausentes(JdbcTemplate j,String c) {
        return contar(j,"select count(*) from tb_paciente where "+c+" is null or regexp_like("+c+", '^\\s*$')");
    }
    private static long inconclusivos(JdbcTemplate j,String c,int comprimento) {
        // H2 usa regex Java, não classes POSIX em colchetes.
        String padrao=c.equals("cpf")?"^[0-9.\\s-]+$":"^[0-9\\s]+$";
        String n=normalizado(c);
        return contar(j,"select count(*) from tb_paciente where "+c+" is not null and not regexp_like("+c+
                ", '^\\s*$') and ("+n+" is null or char_length("+n+")<>"+comprimento+
                " or not regexp_like("+c+", '"+padrao+"'))");
    }
    private static long formatados(JdbcTemplate j,String c) {
        String n=normalizado(c);
        return contar(j,"select count(*) from tb_paciente where "+n+" is not null and "+c+"<>"+n);
    }
    private static long contar(JdbcTemplate j,String sql) {
        return j.queryForObject(sql,Long.class);
    }

    /** Executada antes de liberar o DataSource para ORM, HTTP ou agendadores. */
    public static void verificarOperacional(DataSource source) {
        try {
            verificarRestricoes(source);
            var jdbc = new JdbcTemplate(source);
            Long aplicadas = jdbc.queryForObject("""
                    select count(distinct "version") from "sibraps_agenda_schema_history"
                    where "success"=true and "version" in ('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17')
                    """, Long.class);
            if (!Long.valueOf(17).equals(aplicadas))
                throw new IllegalStateException("Histórico de migração até V17 incompleto.");
            if (analisar(source).bloqueiaV17())
                throw new IllegalStateException("Há documentos duplicados ou inconclusivos. O corte requer revisão cadastral.");
        } catch (SQLException | org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException("Banco operacional sem comprovação da migração offline V17.");
        }
    }

    /** Verificação estrutural, incluindo o conteúdo da expressão calculada. */
    public static void verificarRestricoes(DataSource source) throws SQLException {
        try(var c=source.getConnection()) {
            if(!"H2".equals(c.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Unicidade documental exige validação do SGBD.");
            for(String doc:List.of("CPF","CNS")) {
                String coluna=doc+"_NORMALIZADO";
                try(var q=c.prepareStatement("""
                        select generation_expression from information_schema.columns
                        where table_schema=? and table_name='TB_PACIENTE' and column_name=? and is_generated='ALWAYS'
                        """)) {
                    q.setString(1,c.getSchema()); q.setString(2,coluna);
                    try(var rs=q.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("Coluna documental calculada ausente.");
                        String expressao = rs.getString(1).replace("\"", "").replaceAll("\\s+", "")
                                .toUpperCase(java.util.Locale.ROOT);
                        if (!expressao.equals("NULLIF(REGEXP_REPLACE(" + doc + ",'[^0-9]',''),'')"))
                            throw new IllegalStateException("Expressão de normalização documental inválida.");
                    }
                }
                try(var q=c.prepareStatement("""
                        select count(*) from information_schema.table_constraints tc
                        join information_schema.key_column_usage k
                          on k.constraint_catalog=tc.constraint_catalog and k.constraint_schema=tc.constraint_schema
                          and k.constraint_name=tc.constraint_name
                        where tc.table_schema=? and tc.table_name='TB_PACIENTE' and tc.constraint_type='UNIQUE'
                          and tc.constraint_name=? and k.column_name=?
                          and (select count(*) from information_schema.key_column_usage outro
                               where outro.constraint_catalog=tc.constraint_catalog and outro.constraint_schema=tc.constraint_schema
                                 and outro.constraint_name=tc.constraint_name)=1
                        """)) {
                    q.setString(1,c.getSchema()); q.setString(2,"UK_PACIENTE_"+doc+"_NORMALIZADO"); q.setString(3,coluna);
                    try(var rs=q.executeQuery()) {
                        rs.next(); if(rs.getLong(1)!=1)throw new IllegalStateException("Restrição de unicidade documental ausente.");
                    }
                }
            }
        }
    }
}

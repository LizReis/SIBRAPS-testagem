package com.pet.buscaativa.migration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Evidências de aplicação e observações PRE/FECHADO; não prova hora física de commit. */
public final class EvidenciaResolucaoHistorica {
    private EvidenciaResolucaoHistorica() {}
    private static final String RESOLUCAO="""
            select id,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,
            frequencia_valida,responsavel_valido,fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,
            frequencia_desconsiderada,responsavel_desconsiderado,horario,ator_id,perfil,justificativa,registrado_em
            from tb_resolucao_frequencia_local
            """;
    private static final String REVERSAO="""
            select id,resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado,
            ator_id,perfil,justificativa,registrado_em
            from tb_reversao_resolucao_frequencia
            """;
    private record Chave(String tipo,UUID id) {}
    public record Local(boolean consistente,String assinatura) {}

    private static String assinatura(String tipo,ResultSet rs,int colunas) throws SQLException {
        var digest=EscopoEvidenciaHistorica.digest();
        EscopoEvidenciaHistorica.adicionar(digest,"SIBRAPS-EVIDENCIA-RESOLUCAO-V26");
        EscopoEvidenciaHistorica.adicionar(digest,tipo);
        for(int i=1;i<=colunas;i++) {
            Object v=rs.getObject(i);
            if(v==null) throw new SQLException("Evento de resolução incompleto.");
            // Canonicalizar o instante e a hora local, independentemente do fuso da conexão.
            String valor=v instanceof OffsetDateTime d?d.toInstant().toString()
                    :v instanceof java.sql.Timestamp t?t.toLocalDateTime().toString():v.toString();
            EscopoEvidenciaHistorica.adicionar(digest,valor);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<UUID,String> aplicacoes(JdbcTemplate jdbc,String unidade) {
        return aplicacoes(jdbc,unidade,null,null);
    }

    private static Map<UUID,String> aplicacoes(JdbcTemplate jdbc,String unidade,Long vinculo,Long paciente) {
        if(!AplicacaoPendenciaSchemaGuard.instalada(jdbc)) return Map.of();
        var mapa=new HashMap<UUID,String>();
        jdbc.query("""
                select resolucao_id,pendencia_id,decisao_id,vinculo_id,paciente_id,unidade_chave,decisao
                from tb_aplicacao_pendencia_resolucao where unidade_chave=?
                """+(vinculo==null?"":" and vinculo_id=? and paciente_id=?"),
                rs->{ mapa.put(rs.getObject(1,UUID.class),assinatura("APLICACAO",rs,7)); },
                argumentos(unidade,vinculo,paciente));
        return mapa;
    }

    private static String vincular(String assinatura,String aplicacao) {
        var digest=EscopoEvidenciaHistorica.digest();
        EscopoEvidenciaHistorica.adicionar(digest,assinatura);
        EscopoEvidenciaHistorica.adicionar(digest,aplicacao==null?"SEM_APLICACAO":aplicacao);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Chamado apenas pelo escritor autorizado, após gravar evento e eventual associação. */
    public static void registrar(JdbcTemplate jdbc,String tipo,UUID id,String unidade) {
        boolean resolucao="RESOLUCAO".equals(tipo);
        if(!resolucao && !"REVERSAO".equals(tipo)) throw new IllegalArgumentException("Tipo de evidência inválido.");
        if(!EvidenciaResolucaoSchemaGuard.instalada(jdbc)) throw new IllegalStateException("Evidência V26 ausente.");
        String valor=jdbc.queryForObject((resolucao?RESOLUCAO:REVERSAO)+" where id=? and unidade_chave=?",
                (rs,n)->assinatura(tipo,rs,resolucao?19:11),id,unidade);
        if(resolucao) valor=vincular(valor,aplicacoes(jdbc,unidade).get(id));
        jdbc.update("""
                insert into tb_evidencia_resolucao_historica(tipo,evento_id,unidade_chave,assinatura)
                values (?,?,?,?)
                """,tipo,id,unidade,valor);
    }

    private static Object[] argumentos(String unidade,Long vinculo,Long paciente) {
        return vinculo==null?new Object[]{unidade}:new Object[]{unidade,vinculo,paciente};
    }

    public static Local conferir(JdbcTemplate jdbc,String unidade,Instant corte,String anterior) {
        return conferir(jdbc,unidade,corte,anterior,null,null);
    }

    /** Mesma verificação de assinaturas para o vínculo atual, inclusive reversões.
     * Comprovantes órfãos sem vínculo identificável continuam tratados no fechamento da unidade. */
    public static boolean conferirVinculo(JdbcTemplate jdbc,String unidade,Long vinculo,Long paciente) {
        java.util.Objects.requireNonNull(vinculo);
        java.util.Objects.requireNonNull(paciente);
        return conferir(jdbc,unidade,Instant.MAX,"ATUAL",vinculo,paciente).consistente();
    }

    private static Local conferir(JdbcTemplate jdbc,String unidade,Instant corte,String anterior,Long vinculo,Long paciente) {
        if(!ReversaoResolucaoSchemaGuard.instalada(jdbc)) return new Local(true,anterior);
        String filtro=" where unidade_chave=?"+(vinculo==null?"":" and vinculo_id=? and paciente_id=?");
        var args=argumentos(unidade,vinculo,paciente);
        var digest=EscopoEvidenciaHistorica.digest();
        EscopoEvidenciaHistorica.adicionar(digest,"SIBRAPS-RESOLUCAO-HISTORICA-V2");
        EscopoEvidenciaHistorica.adicionar(digest,anterior);
        var resolucoes=new HashMap<UUID,Instant>();
        var comprovantes=new HashMap<Chave,String>();
        if(EvidenciaResolucaoSchemaGuard.instalada(jdbc)) {
            String consulta="select tipo,evento_id,assinatura from tb_evidencia_resolucao_historica where unidade_chave=?";
            Object[] parametros={unidade};
            if(vinculo!=null) {
                consulta+="""
                         and ((tipo='RESOLUCAO' and evento_id in
                            (select id from tb_resolucao_frequencia_local where unidade_chave=? and vinculo_id=? and paciente_id=?))
                         or (tipo='REVERSAO' and evento_id in
                            (select id from tb_reversao_resolucao_frequencia where unidade_chave=? and vinculo_id=? and paciente_id=?)))
                        """;
                parametros=new Object[]{unidade,unidade,vinculo,paciente,unidade,vinculo,paciente};
            }
            jdbc.query(consulta,rs->{ comprovantes.put(new Chave(rs.getString(1),rs.getObject(2,UUID.class)),rs.getString(3)); },parametros);
        }
        var aplicacoes=aplicacoes(jdbc,unidade,vinculo,paciente);
        boolean[] ok={true};
        jdbc.query(RESOLUCAO+filtro+" order by id",rs->{
            var instante=rs.getObject(19,OffsetDateTime.class).toInstant();
            if(instante.isAfter(corte)) ok[0]=false;
            var id=rs.getObject(1,UUID.class);
            resolucoes.put(id,instante);
            String valor=vincular(assinatura("RESOLUCAO",rs,19),aplicacoes.get(id));
            if(!valor.equals(comprovantes.remove(new Chave("RESOLUCAO",id)))) ok[0]=false;
            EscopoEvidenciaHistorica.adicionar(digest,"RESOLUCAO");
            EscopoEvidenciaHistorica.adicionar(digest,valor);
        },args);
        jdbc.query(REVERSAO+filtro+" order by id",rs->{
            var instante=rs.getObject(11,OffsetDateTime.class).toInstant();
            var original=resolucoes.get(rs.getObject(2,UUID.class));
            if(original==null || instante.isBefore(original) || instante.isAfter(corte)) ok[0]=false;
            String valor=assinatura("REVERSAO",rs,11);
            if(!valor.equals(comprovantes.remove(new Chave("REVERSAO",rs.getObject(1,UUID.class))))) ok[0]=false;
            EscopoEvidenciaHistorica.adicionar(digest,"REVERSAO");
            EscopoEvidenciaHistorica.adicionar(digest,valor);
        },args);
        if(!comprovantes.isEmpty() || !resolucoes.keySet().containsAll(aplicacoes.keySet())) ok[0]=false;
        // Sem eventos, não alterar a assinatura de unidades não afetadas.
        return new Local(ok[0],resolucoes.isEmpty() && ok[0]?anterior:HexFormat.of().formatHex(digest.digest()));
    }
}

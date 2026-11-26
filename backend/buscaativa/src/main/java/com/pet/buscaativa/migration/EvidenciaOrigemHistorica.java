package com.pet.buscaativa.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Evidência de aplicação, reversão e reabertura no mesmo escopo.
 * Não é assinatura externa nem prova do instante físico de commit. */
public final class EvidenciaOrigemHistorica {
    private EvidenciaOrigemHistorica() {}
    public enum Tipo {
        APLICACAO("id", """
                select a.id,a.historico_id,a.vinculo_id,a.paciente_id,a.unidade_chave,a.decisao_id,a.pendencia_id,
                       a.ator_id,a.perfil,a.justificativa,a.registrado_em,p.fonte,p.fonte_id,p.versao_fonte,
                       p.participante_id,p.responsavel_id,p.ocorrido_em,p.frequencia,p.tipo_historico,
                       d.decisao,d.revisor_id,d.perfil_revisor,d.justificativa,d.decidida_em
                from tb_aplicacao_origem_historica a
                join tb_prova_aprovacao_origem p on p.decisao_id=a.decisao_id and p.pendencia_id=a.pendencia_id
                    and p.vinculo_id=a.vinculo_id and p.paciente_id=a.paciente_id
                    and p.unidade_chave=a.unidade_chave and p.historico_id=a.historico_id
                join tb_decisao_revisao_proveniencia d on d.id=p.decisao_id and d.pendencia_id=p.pendencia_id
                    and d.paciente_id=p.paciente_id and d.unidade_chave=p.unidade_chave and d.decisao='APROVAR'
                """),
        REVERSAO("id", """
                select a.id,a.historico_id,a.vinculo_id,a.paciente_id,a.unidade_chave,a.aplicacao_id,
                       a.ator_id,a.perfil,a.justificativa,a.registrado_em
                from tb_reversao_origem_historica a
                """),
        REABERTURA("novo_id", """
                select a.novo_id,a.historico_id,a.vinculo_id,a.paciente_id,a.unidade_chave,a.anterior_id,
                       a.tipo_registro,a.situacao_evidencia,a.motivo,n.solicitante_id,n.perfil_solicitante,
                       n.justificativa,n.solicitado_em,n.estado
                from tb_reabertura_origem_historica a join tb_pendencia_revisao_proveniencia n
                    on n.id=a.novo_id and n.vinculo_id=a.vinculo_id and n.paciente_id=a.paciente_id
                    and n.unidade_chave=a.unidade_chave and n.registro_id=a.historico_id
                    and n.tipo_registro=a.tipo_registro and n.situacao_evidencia=a.situacao_evidencia
                """);
        final String coluna,sql;
        Tipo(String coluna,String sql) {this.coluna=coluna;this.sql=sql;}
        boolean instalada(JdbcTemplate jdbc) {
            return switch(this) {
                case APLICACAO -> AplicacaoOrigemSchemaGuard.instalada(jdbc);
                case REVERSAO -> ReversaoOrigemSchemaGuard.instalada(jdbc);
                case REABERTURA -> ReaberturaOrigemSchemaGuard.instalada(jdbc);
            };
        }
    }
    private record Chave(Tipo tipo,UUID id) {}
    private record Comprovante(long historico,String assinatura) {}
    private static String assinatura(Tipo tipo,ResultSet rs) throws SQLException {
        var digest=EscopoEvidenciaHistorica.digest();
        EscopoEvidenciaHistorica.adicionar(digest,"SIBRAPS-EVIDENCIA-ORIGEM-V31");
        EscopoEvidenciaHistorica.adicionar(digest,tipo.name());
        int colunas=rs.getMetaData().getColumnCount();
        for(int i=1;i<=colunas;i++) {
            Object v=rs.getObject(i);
            EscopoEvidenciaHistorica.adicionar(digest,v==null?"NULO":"VALOR");
            if(v!=null) EscopoEvidenciaHistorica.adicionar(digest,
                    v instanceof OffsetDateTime d?d.toInstant().toString()
                    :v instanceof java.sql.Timestamp t?t.toLocalDateTime().toString():v.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String filtro() {
        return " where a.vinculo_id=? and a.paciente_id=? and a.unidade_chave=?";
    }
    /** Executar no mesmo commit do evento, antes de qualquer projeção. */
    public static void registrar(JdbcTemplate jdbc,Tipo tipo,UUID id,Long vinculo,Long paciente,String unidade,long historico) {
        if(!EvidenciaOrigemSchemaGuard.instalada(jdbc)) throw new IllegalStateException("Evidência de origem V31 ausente.");
        String valor=jdbc.queryForObject(tipo.sql+filtro()+" and a."+tipo.coluna+"=? and a.historico_id=?",
                (rs,n)->assinatura(tipo,rs),vinculo,paciente,unidade,id,historico);
        jdbc.update("""
                insert into tb_evidencia_origem_historica(tipo,evento_id,vinculo_id,paciente_id,unidade_chave,historico_id,assinatura)
                values (?,?,?,?,?,?,?)
                """,tipo.name(),id,vinculo,paciente,unidade,historico,valor);
    }

    /** Não certificar eventos sem comprovante, inclusive os anteriores à V31.
     * A incoerência de um histórico não retira reconhecimento de outro. */
    public static Set<Long> inconsistentes(JdbcTemplate jdbc,Long vinculo,Long paciente,String unidade) {
        Map<Chave,Comprovante> comprovantes=new HashMap<>();
        if(EvidenciaOrigemSchemaGuard.instalada(jdbc)) jdbc.query("""
                select tipo,evento_id,historico_id,assinatura from tb_evidencia_origem_historica
                where vinculo_id=? and paciente_id=? and unidade_chave=?
                """,rs->{comprovantes.put(new Chave(Tipo.valueOf(rs.getString(1)),rs.getObject(2,UUID.class)),
                        new Comprovante(rs.getLong(3),rs.getString(4)));},vinculo,paciente,unidade);
        Set<Long> invalidos=new HashSet<>();
        for(var tipo:Tipo.values()) {
            if(!tipo.instalada(jdbc)) continue;
            jdbc.query(tipo.sql+filtro(),rs->{
                long historico=rs.getLong(2);
                var prova=comprovantes.remove(new Chave(tipo,rs.getObject(1,UUID.class)));
                if(prova==null || prova.historico()!=historico || !prova.assinatura().equals(assinatura(tipo,rs))) {
                    invalidos.add(historico);
                    if(prova!=null) invalidos.add(prova.historico());
                }
            },vinculo,paciente,unidade);
        }
        for(var prova:comprovantes.values()) invalidos.add(prova.historico());
        return Set.copyOf(invalidos);
    }
}

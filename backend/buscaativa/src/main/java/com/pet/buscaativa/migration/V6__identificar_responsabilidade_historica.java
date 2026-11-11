package com.pet.buscaativa.migration;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;

/** Immutable, offline, conservative backfill. No current-coordinator or event-author inference. */
public class V6__identificar_responsabilidade_historica extends BaseJavaMigration {
    @Override public Integer getChecksum(){return 202611106;}
    private record Revision(int rev,int type,Long group,String status){}
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();var performed=new LinkedHashMap<Long,Long>();
        try(var q=connection.createStatement();var rows=q.executeQuery("select id,grupo_id from tb_sessao_grupo where status='REALIZADA' order by id")){
            while(rows.next())performed.put(rows.getLong(1),rows.getLong(2));
        }
        for(var entry:performed.entrySet()){
            Long owner=responsible(connection,entry.getKey(),entry.getValue());
            try(var q=connection.prepareStatement("update tb_sessao_grupo set responsavel_atendimento_id=?,responsabilidade_pendente=?,motivo_responsabilidade_pendente=?,origem_responsabilidade=? where id=?")){
                q.setObject(1,owner);q.setBoolean(2,owner==null);q.setString(3,owner==null?"RESPONSAVEL_HISTORICO_NAO_COMPROVADO":null);
                q.setString(4,owner==null?"PENDENTE_RECONCILIACAO":"ENVERS_TRANSICAO_REALIZADA");q.setLong(5,entry.getKey());
                if(q.executeUpdate()!=1)throw new SQLException("Estado alterado durante migração offline de responsabilidade.");
            }
        }
        try(var q=connection.createStatement()){
            q.execute("ALTER TABLE tb_sessao_grupo ADD CONSTRAINT ck_sessao_responsabilidade CHECK (status <> 'REALIZADA' or ((responsavel_atendimento_id is not null and responsabilidade_pendente = false and motivo_responsabilidade_pendente is null) or (responsavel_atendimento_id is null and responsabilidade_pendente = true and motivo_responsabilidade_pendente is not null)))");
        }
        // Preserve all old IDs, frequency, participant rows, versions, audits and Envers values.
    }
    private Long responsible(Connection c,long session,long liveGroup) throws SQLException {
        var history=new ArrayList<Revision>();
        try(var q=c.prepareStatement("select rev,revtype,grupo_id,status from tb_sessao_grupo_aud where id=? order by rev")){
            q.setLong(1,session);try(var rows=q.executeQuery()){while(rows.next())history.add(new Revision(rows.getInt(1),rows.getInt(2),rows.getObject(3,Long.class),rows.getString(4)));}
        }
        // A first available REALIZADA snapshot alone does not prove the realization coordinator.
        if(history.isEmpty()||history.getFirst().type()!=0||!"AGENDADA".equals(history.getFirst().status()))return null;
        Integer realization=null;String previous=null;
        for(var r:history){
            if(r.type()==2||!Objects.equals(r.group(),liveGroup)||r.status()==null)return null;
            if("REALIZADA".equals(r.status())&&!"REALIZADA".equals(previous)){
                if(realization!=null||!"AGENDADA".equals(previous)||r.type()!=1)return null;
                realization=r.rev();
            }
            if(realization!=null&&!"REALIZADA".equals(r.status()))return null;
            previous=r.status();
        }
        if(realization==null)return null;
        Long responsible=null;boolean creation=false;
        try(var q=c.prepareStatement("select rev,revtype,coordenador_id from tb_grupo_terapeutico_aud where id=? and rev<=? order by rev")){
            q.setLong(1,liveGroup);q.setInt(2,realization);
            try(var rows=q.executeQuery()){
                while(rows.next()){
                    if(!creation){if(rows.getInt(2)!=0)return null;creation=true;}
                    if(rows.getInt(2)==2||rows.getObject(3)==null)return null;
                    responsible=rows.getLong(3);
                }
            }
        }
        if(responsible==null)return null;
        try(var q=c.prepareStatement("select count(*) from tb_usuario where id=?")){q.setLong(1,responsible);try(var rows=q.executeQuery()){rows.next();return rows.getInt(1)==1?responsible:null;}}
    }
}

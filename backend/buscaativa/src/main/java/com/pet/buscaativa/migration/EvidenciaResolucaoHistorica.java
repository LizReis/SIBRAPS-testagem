package com.pet.buscaativa.migration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Complementa a assinatura PRE/FECHADO com os eventos imutáveis V23/V24.
 * Não certifica hora de commit nem substitui a trilha independente V21. */
final class EvidenciaResolucaoHistorica {
    private EvidenciaResolucaoHistorica() {}
    record Local(boolean consistente,String assinatura) {}
    static Local conferir(JdbcTemplate jdbc,String unidade,Instant corte,String anterior) {
        if(!ReversaoResolucaoSchemaGuard.instalada(jdbc)) return new Local(true,anterior);
        var digest=EscopoEvidenciaHistorica.digest();
        EscopoEvidenciaHistorica.adicionar(digest,"SIBRAPS-RESOLUCAO-HISTORICA-V1");
        EscopoEvidenciaHistorica.adicionar(digest,anterior);
        var resolucoes=new HashMap<UUID,Instant>();
        boolean[] ok={true};
        jdbc.query("""
                select id,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,
                frequencia_valida,responsavel_valido,fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,
                frequencia_desconsiderada,responsavel_desconsiderado,horario,ator_id,perfil,justificativa,registrado_em
                from tb_resolucao_frequencia_local where unidade_chave=? order by id
                """,rs->{
            var instante=rs.getObject(19,OffsetDateTime.class).toInstant();
            if(instante.isAfter(corte)) ok[0]=false;
            resolucoes.put(rs.getObject(1,UUID.class),instante);
            EscopoEvidenciaHistorica.adicionar(digest,"RESOLUCAO");
            for(int i=1;i<=19;i++) EscopoEvidenciaHistorica.adicionar(digest,rs.getObject(i).toString());
        },unidade);
        jdbc.query("""
                select id,resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado,
                ator_id,perfil,justificativa,registrado_em
                from tb_reversao_resolucao_frequencia where unidade_chave=? order by id
                """,rs->{
            var instante=rs.getObject(11,OffsetDateTime.class).toInstant();
            var original=resolucoes.get(rs.getObject(2,UUID.class));
            if(original==null || instante.isBefore(original) || instante.isAfter(corte)) ok[0]=false;
            EscopoEvidenciaHistorica.adicionar(digest,"REVERSAO");
            for(int i=1;i<=11;i++) EscopoEvidenciaHistorica.adicionar(digest,rs.getObject(i).toString());
        },unidade);
        // Sem eventos, não alterar a assinatura de unidades não afetadas.
        return new Local(ok[0],resolucoes.isEmpty() && ok[0]?anterior:HexFormat.of().formatHex(digest.digest()));
    }
}

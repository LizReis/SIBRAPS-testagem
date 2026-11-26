package com.pet.buscaativa.migration;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Operação interna; deve participar da MESMA transação serializável do fechamento. */
public final class ObservacaoCoberturaHistorica {
    private ObservacaoCoberturaHistorica() {}
    public record Local(boolean consistente,String assinatura,List<Long> vinculos) {}
    private record Marco(Instant inicio,String zona,String inventario,int unidades,long vinculos) {}

    public static Map<String,Local> observar(JdbcTemplate jdbc,ObjectMapper json,YearMonth mes,ZoneId zona) {
        var marcos=jdbc.query("""
                select m.iniciado_em,m.zona,m.inventario,m.total_unidades,m.total_vinculos
                from tb_marco_tecnico_historico m join tb_base_evidencia_clinica b on b.marco_id=m.id
                where m.id=1
                """,(rs,n)->new Marco(rs.getObject(1,OffsetDateTime.class).toInstant(),rs.getString(2),
                        rs.getString(3),rs.getInt(4),rs.getLong(5)));
        if(marcos.size()!=1) return Map.of();
        var marco=marcos.getFirst();
        if(!zona.getId().equals(marco.zona()) || mes.atDay(1).atStartOfDay(zona).toInstant().isBefore(marco.inicio()))
            return Map.of();
        var raiz=json.readTree(marco.inventario());
        if(!raiz.isObject() || raiz.size()!=marco.unidades()) return Map.of();
        Set<String> unidades=new HashSet<>();
        Set<Long> idsBase=new HashSet<>();
        var campos=raiz.properties();
        long total=0;
        for(var campo:campos) {
            if(!campo.getValue().isArray()) return Map.of();
            String unidade=campo.getKey();
            if(!unidade.matches("USF:[1-9][0-9]*|CAPS_AD|CAPS_I|CAPS_II")) return Map.of();
            unidades.add(unidade);
            for(var item:campo.getValue()) {
                if(!item.path("id").isIntegralNumber() || !item.path("paciente").isIntegralNumber()
                        || item.path("id").asLong()<=0 || item.path("paciente").asLong()<=0
                        || !idsBase.add(item.path("id").asLong())) return Map.of();
                total++;
            }
        }
        if(total!=marco.vinculos()) return Map.of();
        var provas=ReconciliacaoEvidenciaClinica.conferirLocais(jdbc,EscopoEvidenciaHistorica.carregar(jdbc),unidades);
        Map<String,Local> resultado=new TreeMap<>();
        for(var e:provas.entrySet()) {
            var ids=jdbc.queryForList("select id from tb_vinculo_assistencial where unidade_chave=? order by id",
                    Long.class,e.getKey());
            var resolucoes=EvidenciaResolucaoHistorica.conferir(jdbc,e.getKey(),
                    mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant().minusMillis(1),e.getValue().assinatura());
            resultado.put(e.getKey(),new Local(e.getValue().consistente() && resolucoes.consistente(),
                    resolucoes.assinatura(),List.copyOf(ids)));
        }
        return resultado;
    }
}

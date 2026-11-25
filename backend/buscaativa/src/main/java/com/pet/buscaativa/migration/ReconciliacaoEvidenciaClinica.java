package com.pet.buscaativa.migration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Diagnóstico de integridade em um snapshot consistente. Não é certificação de cobertura mensal:
 * a hora de DML e a revisão Envers não representam necessariamente a hora do commit.
 * Nenhum identificador, assinatura ou dado de paciente é retornado.
 */
public final class ReconciliacaoEvidenciaClinica {
    private ReconciliacaoEvidenciaClinica() {}

    public record Fonte(long baseDivergente, long movimentosIncoerentes, long auditoriaDivergente,
            long estadoAtualDivergente, long revisoesAmbiguas) {
        public boolean consistente() {
            return baseDivergente == 0 && movimentosIncoerentes == 0 && auditoriaDivergente == 0
                    && estadoAtualDivergente == 0 && revisoesAmbiguas == 0;
        }
    }

    public record Diagnostico(boolean baseDisponivel, boolean cardinalidadeConfere, Map<String, Fonte> fontes) {
        public boolean consistente() {
            return baseDisponivel && cardinalidadeConfere && fontes.size() == AssinaturaEvidenciaClinica.FONTES.size()
                    && fontes.values().stream().allMatch(Fonte::consistente);
        }
    }

    private record Base(long ultimo, long total, OffsetDateTime marco) {}
    private record Movimento(long movimento, Long anterior, Long atual, String antes, String depois, String operacao) {}
    private record Revisao(long id, long rev, int tipo, Long instante, String assinatura) {}

    public static Diagnostico diagnosticar(DataSource fonte) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(fonte));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        tx.setReadOnly(true);
        return Objects.requireNonNull(tx.execute(status -> diagnosticar(new JdbcTemplate(fonte))));
    }

    private static Diagnostico diagnosticar(JdbcTemplate jdbc) {
        if (!EvidenciaClinicaSchemaGuard.instalada(jdbc))
            return new Diagnostico(false, false, Map.of());
        var bases = jdbc.query("""
                select b.ultimo_movimento,b.total_registros,m.iniciado_em
                from tb_base_evidencia_clinica b join tb_marco_tecnico_historico m on m.id=b.marco_id
                where b.marco_id=1
                """, (rs,n) -> new Base(rs.getLong(1), rs.getLong(2), rs.getObject(3,OffsetDateTime.class)));
        if (bases.size() != 1) return new Diagnostico(false, false, Map.of());
        var base = bases.getFirst();
        long total = jdbc.queryForObject("select count(*) from tb_item_base_evidencia_clinica where marco_id=1",Long.class);
        var fontes = new TreeMap<String,Fonte>();
        for (var fonte : AssinaturaEvidenciaClinica.FONTES.entrySet())
            fontes.put(fonte.getKey(), conferir(jdbc, base, fonte.getKey(), fonte.getValue()).resumo());
        return new Diagnostico(true, total == base.total(), Map.copyOf(fontes));
    }

    record Conferencia(Fonte resumo, java.util.Set<Long> divergentes, Map<Long,List<String>> marcas) {}
    record Local(boolean consistente,String assinatura) {}

    /** Usada somente na transação serializável da observação mensal, sem HTTP. */
    static Map<String, Local> conferirLocais(JdbcTemplate jdbc, EscopoEvidenciaHistorica escopo, java.util.Set<String> unidades) {
        var bases = jdbc.query("""
                select b.ultimo_movimento,b.total_registros,m.iniciado_em
                from tb_base_evidencia_clinica b join tb_marco_tecnico_historico m on m.id=b.marco_id
                where b.marco_id=1
                """, (rs,n) -> new Base(rs.getLong(1),rs.getLong(2),rs.getObject(3,OffsetDateTime.class)));
        if (bases.size()!=1) return Map.of();
        var base=bases.getFirst();
        boolean cardinalidade=jdbc.queryForObject("select count(*) from tb_item_base_evidencia_clinica where marco_id=1",Long.class)==base.total();
        Map<String, Conferencia> provas=new TreeMap<>();
        for (var fonte:AssinaturaEvidenciaClinica.FONTES.entrySet())
            provas.put(fonte.getKey(),conferir(jdbc,base,fonte.getKey(),fonte.getValue()));
        Map<String,Local> resultado=new TreeMap<>();
        for (String unidade:unidades) {
            boolean ok=cardinalidade;
            var digest=EscopoEvidenciaHistorica.digest();
            for (var fonte:provas.entrySet()) {
                EscopoEvidenciaHistorica.adicionar(digest,fonte.getKey());
                var prova=fonte.getValue();
                for (var item:new TreeMap<>(prova.marcas()).entrySet()) {
                    var escopos=escopo.unidades(fonte.getKey(),item.getKey());
                    // Registro sem localização em fonte, revisão ou V19: não atribuir por suposição.
                    if (escopos==null) { ok=false; continue; }
                    if (!escopos.contains(unidade)) continue;
                    if (prova.divergentes().contains(item.getKey())) ok=false;
                    EscopoEvidenciaHistorica.adicionar(digest,item.getKey().toString());
                    for (String marca:item.getValue()) EscopoEvidenciaHistorica.adicionar(digest,marca);
                }
            }
            resultado.put(unidade,new Local(ok,java.util.HexFormat.of().formatHex(digest.digest())));
        }
        return resultado;
    }

    private static Conferencia conferir(JdbcTemplate jdbc, Base marco, String fonte, List<String> colunas) {
        var base = new HashMap<Long,String>();
        jdbc.query("""
                select registro,assinatura from tb_item_base_evidencia_clinica where marco_id=1 and fonte=?
                """, rs -> { base.put(rs.getLong(1),rs.getString(2)); }, fonte);
        var marcas = new HashMap<Long,List<String>>();
        var divergentes = new HashSet<Long>();
        base.forEach((id,assinatura)->marcar(marcas,id,"B:"+assinatura));
        var estado = new HashMap<>(base);
        var transicoes = new HashMap<Long,List<String>>();
        long incoerencias = 0;
        var movimentos = jdbc.query("""
                select id,registro_anterior,registro_atual,assinatura_anterior,assinatura_atual,operacao
                from tb_movimento_evidencia_clinica where fonte=? and id>? order by id
                """, (rs,n) -> new Movimento(rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Long.class),
                        rs.getString(4),rs.getString(5),rs.getString(6)), fonte, marco.ultimo());
        for (var m : movimentos) {
            boolean formato = switch (m.operacao()) {
                case "INCLUSAO" -> m.anterior()==null && m.antes()==null && m.atual()!=null && assinaturaValida(m.depois());
                case "EXCLUSAO" -> m.atual()==null && m.depois()==null && m.anterior()!=null && assinaturaValida(m.antes());
                case "ALTERACAO" -> m.anterior()!=null && m.anterior().equals(m.atual())
                        && assinaturaValida(m.antes()) && assinaturaValida(m.depois()) && !m.antes().equals(m.depois());
                default -> false;
            };
            if (m.anterior()!=null) marcar(marcas,m.anterior(),"M:"+m);
            if (m.atual()!=null && !m.atual().equals(m.anterior())) marcar(marcas,m.atual(),"M:"+m);
            if (!formato) {
                incoerencias++;
                if (m.anterior()!=null) divergentes.add(m.anterior());
                if (m.atual()!=null) divergentes.add(m.atual());
                continue;
            }
            long id = m.anterior()!=null ? m.anterior() : m.atual();
            if (!Objects.equals(estado.get(id),m.antes())) { incoerencias++; divergentes.add(id); continue; }
            alterar(estado,transicoes,id,m.depois());
        }

        var baseAud = new HashMap<Long,String>();
        var estadoAud = new HashMap<Long,String>();
        var transicoesAud = new HashMap<Long,List<String>>();
        var posteriores = new ArrayList<Revisao>();
        long ambiguas = 0;
        long marcoMs = marco.marco().toInstant().toEpochMilli();
        String projeção = colunas.stream().map(c -> "a."+c).collect(java.util.stream.Collectors.joining(","));
        var revisoes = jdbc.query("select "+projeção+",a.revtype,r.revtstmp,a.rev from "+fonte+"_AUD a"
                +" left join revinfo r on a.rev=r.rev order by a.rev,a.id", (rs,n) -> {
            var valores = new Object[colunas.size()];
            for (int i=0;i<valores.length;i++) valores[i]=rs.getObject(i+1);
            Integer tipo = rs.getObject(colunas.size()+1,Integer.class);
            return new Revisao(rs.getLong(1),rs.getLong(colunas.size()+3), tipo==null?-1:tipo,rs.getObject(colunas.size()+2,Long.class),
                    tipo!=null && tipo!=2 ? AssinaturaEvidenciaClinica.calcular(fonte,valores) : null);
        });
        // A base foi obtida offline; igualdade de milissegundo ou inversão de revisões
        // na fronteira exige investigação, não escolha arbitrária de um lado do marco.
        boolean passouMarco = false;
        for (var r : revisoes) {
            marcar(marcas,r.id(),"R:"+r);
            if (r.instante()==null || r.tipo()<0 || r.tipo()>2 || r.instante()==marcoMs) { ambiguas++; divergentes.add(r.id()); continue; }
            if (r.instante()<marcoMs) {
                if (passouMarco) { ambiguas++; divergentes.add(r.id()); }
                if (r.tipo()==2) baseAud.remove(r.id()); else baseAud.put(r.id(),r.assinatura());
            } else {
                passouMarco=true;
                posteriores.add(r);
            }
        }
        estadoAud.putAll(baseAud);
        for (var r : posteriores) {
            boolean existe = estadoAud.containsKey(r.id());
            if ((r.tipo()==0 && existe) || (r.tipo()!=0 && !existe)) { ambiguas++; divergentes.add(r.id()); }
            alterar(estadoAud,transicoesAud,r.id(),r.assinatura());
        }

        var atual = new HashMap<Long,String>();
        jdbc.query("select "+String.join(",",colunas)+" from "+fonte,rs -> {
            var valores = new Object[colunas.size()];
            for (int i=0;i<valores.length;i++) valores[i]=rs.getObject(i+1);
            atual.put(rs.getLong(1),AssinaturaEvidenciaClinica.calcular(fonte,valores));
        });
        atual.forEach((id,assinatura)->marcar(marcas,id,"A:"+assinatura));
        var dBase=idsDivergentes(base,baseAud);
        var dAud=idsDivergentes(transicoes,transicoesAud);
        var dAtual=idsDivergentes(estado,atual);
        divergentes.addAll(dBase); divergentes.addAll(dAud); divergentes.addAll(dAtual);
        return new Conferencia(new Fonte(dBase.size(),incoerencias,dAud.size(),dAtual.size(),ambiguas),divergentes,marcas);
    }

    private static boolean assinaturaValida(String valor) {
        return valor!=null && valor.matches("[0-9a-f]{64}");
    }

    private static void alterar(Map<Long,String> estado, Map<Long,List<String>> transicoes, long id, String valor) {
        if (Objects.equals(estado.get(id),valor)) return;
        transicoes.computeIfAbsent(id,k->new ArrayList<>()).add(valor);
        if (valor==null) estado.remove(id); else estado.put(id,valor);
    }

    private static void marcar(Map<Long,List<String>> marcas,long id,String valor) {
        marcas.computeIfAbsent(id,k->new ArrayList<>()).add(valor);
    }

    private static java.util.Set<Long> idsDivergentes(Map<Long,?> a, Map<Long,?> b) {
        var ids = new HashSet<>(a.keySet());
        ids.addAll(b.keySet());
        ids.removeIf(id->Objects.equals(a.get(id),b.get(id)));
        return ids;
    }
}

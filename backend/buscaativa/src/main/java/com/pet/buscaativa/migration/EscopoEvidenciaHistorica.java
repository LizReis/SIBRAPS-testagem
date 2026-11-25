package com.pet.buscaativa.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atribuição conservadora de evidências, inclusive registros removidos. Nunca usa a lotação atual do profissional. */
final class EscopoEvidenciaHistorica {
    private final Map<String,Map<Long,Set<String>>> fontes=new HashMap<>();
    private final Map<Long,Set<String>> pessoas=new HashMap<>();
    private final Map<Long,Set<Long>> gruposPorSessao=new HashMap<>();
    private final Set<Long> grupoIncerto=new HashSet<>(), sessaoIncerta=new HashSet<>();

    private EscopoEvidenciaHistorica() {
        AssinaturaEvidenciaClinica.FONTES.keySet().forEach(f->fontes.put(f,new HashMap<>()));
    }

    static EscopoEvidenciaHistorica carregar(JdbcTemplate jdbc) {
        var e=new EscopoEvidenciaHistorica();
        jdbc.query(uniao("TB_VINCULO_ASSISTENCIAL","id,paciente_id,unidade_chave"),rs->{
            e.vinculo(rs.getLong(1),rs.getLong(2),rs.getString(3));
        });
        jdbc.query("""
                select vinculo_anterior,paciente_anterior,unidade_anterior,vinculo_atual,paciente_atual,unidade_atual
                from tb_movimento_inventario_historico
                """,rs->{
            if (rs.getObject(1)!=null) e.vinculo(rs.getLong(1),rs.getLong(2),rs.getString(3));
            if (rs.getObject(4)!=null) e.vinculo(rs.getLong(4),rs.getLong(5),rs.getString(6));
        });
        jdbc.query(uniao("TB_PACIENTE","id"),rs->{ e.registro("TB_PACIENTE",rs.getLong(1)).addAll(e.pessoa(rs.getLong(1))); });
        jdbc.query(uniao("TB_AGENDAMENTO","id,paciente_id,unidade_origem_chave"),rs->{
            var locais=e.registro("TB_AGENDAMENTO",rs.getLong(1));
            if (valida(rs.getString(3))) locais.add(rs.getString(3));
            else locais.addAll(e.pessoa(rs.getLong(2)));
        });
        jdbc.query(uniao("TB_GRUPO_TERAPEUTICO","id,unidade_origem_chave"),rs->{
            var id=rs.getLong(1);
            var locais=e.registro("TB_GRUPO_TERAPEUTICO",id);
            if (valida(rs.getString(2))) locais.add(rs.getString(2)); else e.grupoIncerto.add(id);
        });
        jdbc.query(uniao("TB_SESSAO_GRUPO","id,grupo_id,unidade_origem_chave"),rs->{
            var id=rs.getLong(1); var grupo=rs.getLong(2);
            var locais=e.registro("TB_SESSAO_GRUPO",id);
            e.gruposPorSessao.computeIfAbsent(id,k->new HashSet<>()).add(grupo);
            if (valida(rs.getString(3))) locais.add(rs.getString(3)); else e.sessaoIncerta.add(id);
            var origemGrupo=e.unidades("TB_GRUPO_TERAPEUTICO",grupo);
            if (origemGrupo==null || e.grupoIncerto.contains(grupo)) e.sessaoIncerta.add(id);
            if (origemGrupo!=null) locais.addAll(origemGrupo);
        });
        var participantes=jdbc.query(uniao("TB_SESSAO_GRUPO_PARTICIPANTE","id,paciente_id,sessao_grupo_id"),
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)});
        // Quando a origem é inconclusiva, todas as unidades possíveis do paciente são afetadas,
        // sem atribuir o fato a nenhuma delas.
        for (var p:participantes) {
            var sessao=e.unidades("TB_SESSAO_GRUPO",p[2]);
            if (sessao!=null && e.sessaoIncerta.contains(p[2])) {
                sessao.addAll(e.pessoa(p[1]));
                for (long grupo:e.gruposPorSessao.getOrDefault(p[2],Set.of())) {
                    var locais=e.unidades("TB_GRUPO_TERAPEUTICO",grupo);
                    if (locais!=null && e.grupoIncerto.contains(grupo)) locais.addAll(e.pessoa(p[1]));
                }
            }
        }
        for (var p:participantes) {
            var locais=e.registro("TB_SESSAO_GRUPO_PARTICIPANTE",p[0]);
            var sessao=e.unidades("TB_SESSAO_GRUPO",p[2]);
            if (sessao!=null) locais.addAll(sessao);
            if (sessao==null || e.sessaoIncerta.contains(p[2])) locais.addAll(e.pessoa(p[1]));
        }
        jdbc.query(uniao("TB_EVENTO_ESTADO_CLINICO_VINCULO","id,vinculo_id,unidade_chave"),rs->{
            var locais=e.registro("TB_EVENTO_ESTADO_CLINICO_VINCULO",rs.getLong(1));
            if (valida(rs.getString(3))) locais.add(rs.getString(3));
            var vinculo=e.unidades("TB_VINCULO_ASSISTENCIAL",rs.getLong(2));
            if (vinculo!=null) locais.addAll(vinculo);
        });
        return e;
    }

    private void vinculo(long id,long pessoa,String unidade) {
        if (!valida(unidade)) throw new IllegalStateException("Escopo assistencial inconclusivo.");
        registro("TB_VINCULO_ASSISTENCIAL",id).add(unidade);
        pessoas.computeIfAbsent(pessoa,k->new HashSet<>()).add(unidade);
    }

    private Set<String> pessoa(long id) { return pessoas.getOrDefault(id,Set.of()); }
    private Set<String> registro(String fonte,long id) { return fontes.get(fonte).computeIfAbsent(id,k->new HashSet<>()); }
    Set<String> unidades(String fonte,long id) { return fontes.get(fonte).get(id); }

    private static String uniao(String tabela,String colunas) {
        return "select "+colunas+" from "+tabela+" union select "+colunas+" from "+tabela+"_AUD where revtype<>2";
    }

    private static boolean valida(String unidade) {
        return unidade!=null && (unidade.matches("USF:[1-9][0-9]*") || List.of("CAPS_I","CAPS_II","CAPS_AD").contains(unidade));
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void adicionar(MessageDigest digest,String texto) {
        byte[] bytes=texto.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

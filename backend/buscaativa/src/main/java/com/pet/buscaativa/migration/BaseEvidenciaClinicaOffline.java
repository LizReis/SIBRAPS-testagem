package com.pet.buscaativa.migration;

import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executada somente dentro da transação exclusiva que cria o marco V20. */
final class BaseEvidenciaClinicaOffline {
    private BaseEvidenciaClinicaOffline() {}

    static boolean existe(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from tb_base_evidencia_clinica where marco_id=1)", Boolean.class));
    }

    static void registrar(JdbcTemplate jdbc) {
        long ultimo = jdbc.queryForObject("select coalesce(max(id),0) from tb_movimento_evidencia_clinica", Long.class);
        long total = 0;
        for (var tabela : AssinaturaEvidenciaClinica.FONTES.keySet())
            total += jdbc.queryForObject("select count(*) from "+tabela, Long.class);
        jdbc.update("insert into tb_base_evidencia_clinica(marco_id,ultimo_movimento,total_registros) values (1,?,?)", ultimo,total);
        for (var fonte : AssinaturaEvidenciaClinica.FONTES.entrySet()) {
            var linhas = new ArrayList<Object[]>();
            jdbc.query("select "+String.join(",",fonte.getValue())+" from "+fonte.getKey()+" order by id", rs -> {
                var valores = new Object[fonte.getValue().size()];
                for (int i=0; i<valores.length; i++) valores[i]=rs.getObject(i+1);
                linhas.add(new Object[]{fonte.getKey(),rs.getLong(1),AssinaturaEvidenciaClinica.calcular(fonte.getKey(),valores)});
            });
            jdbc.batchUpdate("""
                    insert into tb_item_base_evidencia_clinica(marco_id,fonte,registro,assinatura) values (1,?,?,?)
                    """,linhas);
        }
        if (jdbc.queryForObject("select count(*) from tb_item_base_evidencia_clinica where marco_id=1",Long.class)!=total)
            throw new IllegalStateException("População da base clínica não conferiu.");
    }
}

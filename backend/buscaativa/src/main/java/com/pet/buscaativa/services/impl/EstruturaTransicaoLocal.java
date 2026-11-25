package com.pet.buscaativa.services.impl;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.services.exceptions.ConflictException;
import lombok.RequiredArgsConstructor;

@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
class EstruturaTransicaoLocal {
    private final JdbcTemplate jdbc;
    private final ModeloClinico modelo;

    boolean disponivel() {
        boolean tabelas = Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=3 from information_schema.tables where table_schema=current_schema()
                and table_name in ('TB_MARCO_HISTORICO_VINCULO','TB_FECHAMENTO_HISTORICO_VINCULO','TB_REVISAO_ENTREGA_RF26')
                """, Boolean.class));
        if (!tabelas || !modelo.operacional()) return tabelas;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from "sibraps_agenda_schema_history" where "version"='18' and "success"=true)
                """, Boolean.class));
    }

    void exigir() {
        if (!disponivel()) throw new ConflictException("A transição requer a instalação offline da V18.");
    }
}

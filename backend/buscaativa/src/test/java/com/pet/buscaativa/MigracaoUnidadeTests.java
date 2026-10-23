package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MigracaoUnidadeTests {
    @Test
    void migracaoAditivaPreservaUsuariosEHistorico() throws Exception {
        try (var conexao = DriverManager.getConnection("jdbc:h2:mem:migracao-" + UUID.randomUUID(), "sa", "");
             var sql = conexao.createStatement()) {
            sql.execute("create table tb_usf_referencia (id bigint primary key)");
            sql.execute("create table tb_usuario (id bigint primary key, unidade_atuacao integer, email varchar)");
            sql.execute("create table tb_usuario_aud (id bigint, rev integer, unidade_atuacao integer)");
            sql.execute("insert into tb_usf_referencia values (7)");
            sql.execute("insert into tb_usuario values (1, 1, 'legado@test.local')");
            sql.execute("insert into tb_usuario_aud values (1, 1, 1)");
            String script = Files.readString(Path.of("db/isolamento-unidade.sql")).replaceAll("(?m)^--.*$", "");
            for (String comando : script.split(";")) if (!comando.isBlank()) sql.execute(comando);
            try (var r = sql.executeQuery("select * from tb_usuario where id = 1")) {
                assertTrue(r.next());
                assertEquals("legado@test.local", r.getString("email"));
                assertEquals(1, r.getInt("unidade_atuacao"));
                assertNull(r.getObject("usf_atuacao_id"));
            }
            try (var r = sql.executeQuery("select * from tb_usuario_aud where id = 1")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt("rev"));
                assertNull(r.getObject("usf_atuacao_id"));
            }
            assertThrows(SQLException.class, () -> sql.execute("update tb_usuario set usf_atuacao_id = 9 where id = 1"));
            sql.execute("update tb_usuario set usf_atuacao_id = 7 where id = 1");
            assertThrows(SQLException.class, () -> sql.execute("delete from tb_usf_referencia where id = 7"));
        }
    }
}

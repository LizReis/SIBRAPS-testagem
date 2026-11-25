package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.BuscaAtivaPainelLocalEnsaioService.Evolucao;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class FechamentoHistoricoLocalService {
    private final JdbcTemplate jdbc;
    private final EstruturaTransicaoLocal estrutura;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AgendaAssistencialLocks locks;
    private final HistoricoRiscoVinculoEnsaioService historico;
    private final ObjectMapper json;
    private final Clock clock;

    public record Marco(Instant iniciadoEm, String zona) {}
    public record Manifesto(List<Long> vinculos, String evidenciaSha256) {}
    private record Fechamento(String sha256, String inventario, String indicadores) {}
    record Series(Map<String, Evolucao> valores) {}

    boolean habilitado() {
        return estrutura.disponivel();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Marco iniciar() {
        var ator = atores.autorizar(true);
        estrutura.exigir();
        atores.validar(ator, null, Map.of(ator.ator(), locks.travarUsuario(ator.ator())));
        var existente = marcos(ator.unidade(), false);
        if (!existente.isEmpty()) return existente.getFirst();
        var inventario = jdbc.queryForList("""
                select id from tb_vinculo_assistencial where unidade_chave=? order by id
                """, Long.class, ator.unidade());
        // Uma precisão coerente com a coluna impede mover o marco para trás na fronteira do mês.
        var inicio = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try {
            jdbc.update("""
                    insert into tb_marco_historico_vinculo(unidade,iniciado_em,zona,administrador,inventario)
                    values (?,?,?,?,?)
                    """, ator.unidade(), data(inicio), clock.getZone().getId(), ator.ator(), json.writeValueAsString(inventario));
        } catch (DuplicateKeyException concorrente) {
            throw new ConflictException("O marco da unidade já foi registrado.");
        }
        return new Marco(inicio, clock.getZone().getId());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Evolucao fechar(String referencia, Manifesto manifesto) {
        var ator = atores.autorizar(true);
        estrutura.exigir();
        var mes = mes(referencia);
        var ids = ids(manifesto);
        var sha = manifesto.evidenciaSha256().toLowerCase(java.util.Locale.ROOT);
        // A linha do marco serializa as certificações e fixa a timezone do período.
        var marco = marcos(ator.unidade(), true).stream().findFirst()
                .orElseThrow(() -> new ConflictException("Unidade sem marco histórico."));
        atores.validar(ator, null, Map.of(ator.ator(), locks.travarUsuario(ator.ator())));
        var inicio = mes.atDay(1).atStartOfDay(clock.getZone()).toInstant();
        if (!clock.getZone().getId().equals(marco.zona()) || inicio.isBefore(marco.iniciadoEm())
                || !mes.isBefore(YearMonth.now(clock)))
            throw new ConflictException("Período fora da cobertura ou ainda aberto.");
        var inventario = json.writeValueAsString(ids.stream().sorted().toList());
        var anterior = fechado(ator.unidade(), mes);
        if (!anterior.isEmpty()) {
            var registro = anterior.getFirst();
            if (!sha.equals(registro.sha256()) || !inventario.equals(registro.inventario()))
                throw new ConflictException("O fechamento do período é imutável.");
            return indicador(registro, null, null);
        }
        if (!historico.inventarioConfere(ator.unidade(), mes, ids))
            throw new ConflictException("O inventário independente diverge das evidências temporais.");

        Map<String, Evolucao> indicadores = new LinkedHashMap<>();
        for (var tipo : java.util.Arrays.asList(null, TipoAcompanhamento.INDIVIDUAL,
                TipoAcompanhamento.GRUPO_TERAPEUTICO, TipoAcompanhamento.AMBOS)) {
            for (Boolean rua : java.util.Arrays.asList(null, true, false)) {
                indicadores.put(chave(tipo, rua), historico.conferirFechamento(ator.unidade(), mes, ids, tipo, rua));
            }
        }
        try {
            jdbc.update("""
                    insert into tb_fechamento_historico_vinculo
                    (unidade,mes,corte,certificado_em,administrador,evidencia_sha256,inventario,indicadores)
                    values (?,?,?,?,?,?,?,?)
                    """, ator.unidade(), mes.atDay(1), data(HistoricoRiscoVinculoEnsaioService.corte(mes, clock.getZone())),
                    data(clock.instant()), ator.ator(), sha, inventario, json.writeValueAsString(new Series(indicadores)));
        } catch (DuplicateKeyException concorrente) {
            throw new ConflictException("Fechamento registrado em outra transação. Consulte o período novamente.");
        }
        return indicadores.get(chave(null, null));
    }

    List<Evolucao> ler(String unidade, List<YearMonth> meses, TipoAcompanhamento tipo, Boolean rua) {
        return meses.stream().map(mes -> {
            if (!mes.isBefore(YearMonth.now(clock))) return HistoricoRiscoVinculoEnsaioService.semCobertura(mes);
            var registro = fechado(unidade, mes);
            return registro.isEmpty() ? HistoricoRiscoVinculoEnsaioService.semCobertura(mes)
                    : indicador(registro.getFirst(), tipo, rua);
        }).toList();
    }

    private Evolucao indicador(Fechamento registro, TipoAcompanhamento tipo, Boolean rua) {
        return json.readValue(registro.indicadores(), Series.class).valores().get(chave(tipo, rua));
    }

    private List<Marco> marcos(String unidade, boolean bloquear) {
        return jdbc.query("select iniciado_em,zona from tb_marco_historico_vinculo where unidade=?"
                + (bloquear ? " for update" : ""), (rs, linha) -> new Marco(rs.getObject(1, OffsetDateTime.class).toInstant(),
                        rs.getString(2)), unidade);
    }

    private List<Fechamento> fechado(String unidade, YearMonth mes) {
        return jdbc.query("""
                select evidencia_sha256,inventario,indicadores from tb_fechamento_historico_vinculo
                where unidade=? and mes=?
                """, (rs, linha) -> new Fechamento(rs.getString(1), rs.getString(2), rs.getString(3)), unidade, mes.atDay(1));
    }

    private static String chave(TipoAcompanhamento tipo, Boolean rua) {
        return String.valueOf(tipo) + "/" + rua;
    }

    private static Set<Long> ids(Manifesto dados) {
        if (dados == null || dados.vinculos() == null || dados.evidenciaSha256() == null
                || !dados.evidenciaSha256().matches("[a-fA-F0-9]{64}")
                || dados.vinculos().stream().anyMatch(id -> id == null || id <= 0))
            throw new ValidationException("Informe o inventário independente e o SHA-256 da evidência de fechamento.");
        var ids = new HashSet<>(dados.vinculos());
        if (ids.size() != dados.vinculos().size()) throw new ValidationException("Inventário contém vínculos repetidos.");
        return ids;
    }

    private static YearMonth mes(String referencia) {
        try {
            return YearMonth.parse(referencia);
        } catch (java.time.format.DateTimeParseException invalido) {
            throw new ValidationException("Informe um mês no formato AAAA-MM.");
        }
    }

    private static OffsetDateTime data(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}

package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.migration.FechamentoAutomaticoSchemaGuard;
import com.pet.buscaativa.migration.ObservacaoCoberturaHistorica;
import com.pet.buscaativa.services.impl.BuscaAtivaPainelLocalEnsaioService.Evolucao;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("!test")
@RequiredArgsConstructor
public class FechamentoHistoricoAutomaticoService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final HistoricoRiscoVinculoEnsaioService historico;

    record Janela(YearMonth mes,boolean antes,Instant limite) {}
    record Pre(Instant observado,String assinatura,String inventario) {}
    public record Resumo(int observados,int fechados,int semProva) {}

    static Janela janela(Instant agora,java.time.ZoneId zona) {
        var local=agora.atZone(zona);
        var mes=YearMonth.from(local);
        var inicio=mes.atDay(1).atStartOfDay(zona).toInstant();
        var fim=mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant();
        if(!agora.isBefore(fim.minusSeconds(60)) && agora.isBefore(fim)) return new Janela(mes,true,fim);
        if(!agora.isBefore(inicio) && agora.isBefore(inicio.plusSeconds(300))) return new Janela(mes.minusMonths(1),false,inicio);
        return null;
    }

    @Transactional(isolation=Isolation.SERIALIZABLE)
    public Resumo executar() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)
            throw new IllegalStateException("Fechamento exige contexto técnico.");
        var instante=clock.instant();
        var janela=janela(instante,clock.getZone());
        if(janela==null || !FechamentoAutomaticoSchemaGuard.instalada(jdbc)) return new Resumo(0,0,0);
        // A primeira leitura persistente e todas as seguintes pertencem ao snapshot serializável.
        var locais=ObservacaoCoberturaHistorica.observar(jdbc,json,janela.mes(),clock.getZone());
        if(!janela.equals(janela(clock.instant(),clock.getZone()))) return new Resumo(0,0,locais.size());
        int observados=0,fechados=0,semProva=0;
        for(var e:locais.entrySet()) {
            String unidade=e.getKey();
            var prova=e.getValue();
            String fase=janela.antes()?"PRE":"FECHADO";
            if(existe(unidade,janela.mes(),fase) || legado(unidade,janela.mes())) continue;
            var inventario=json.writeValueAsString(prova.vinculos());
            if(janela.antes()) {
                if(!prova.consistente()) {semProva++;continue;}
                gravar(unidade,janela,instante,prova.assinatura(),inventario,null);
                observados++;
            } else {
                var pre=jdbc.query("""
                        select observado_em,assinatura,inventario from tb_observacao_historico_mensal
                        where unidade=? and mes=? and fase='PRE'
                        """,(rs,n)->new Pre(rs.getObject(1,OffsetDateTime.class).toInstant(),rs.getString(2),rs.getString(3)),
                        unidade,janela.mes().atDay(1));
                if(pre.size()!=1 || !confere(pre.getFirst(),janela,prova,inventario)) {semProva++;continue;}
                Map<String,Evolucao> valores=new LinkedHashMap<>();
                for(var tipo:java.util.Arrays.asList(null,TipoAcompanhamento.INDIVIDUAL,
                        TipoAcompanhamento.GRUPO_TERAPEUTICO,TipoAcompanhamento.AMBOS))
                    for(Boolean rua:java.util.Arrays.asList(null,true,false))
                        valores.put(FechamentoHistoricoLocalService.chave(tipo,rua),
                                historico.conferirFechamento(unidade,janela.mes(),Set.copyOf(prova.vinculos()),tipo,rua));
                if(!janela.equals(janela(clock.instant(),clock.getZone()))) throw new IllegalStateException("Janela de fechamento excedida.");
                gravar(unidade,janela,instante,prova.assinatura(),inventario,
                        json.writeValueAsString(new FechamentoHistoricoLocalService.Series(valores)));
                fechados++;
            }
        }
        if(!janela.equals(janela(clock.instant(),clock.getZone()))) throw new IllegalStateException("Janela de fechamento excedida.");
        return new Resumo(observados,fechados,semProva);
    }

    static boolean confere(Pre pre,Janela janela,ObservacaoCoberturaHistorica.Local prova,String inventario) {
        return !janela.antes() && prova.consistente()
                && !pre.observado().isBefore(janela.limite().minusSeconds(60))
                && pre.observado().isBefore(janela.limite())
                && pre.assinatura().equals(prova.assinatura()) && pre.inventario().equals(inventario);
    }

    private boolean existe(String unidade,YearMonth mes,String fase) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_observacao_historico_mensal where unidade=? and mes=? and fase=?)
                """,Boolean.class,unidade,mes.atDay(1),fase));
    }

    private boolean legado(String unidade,YearMonth mes) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_fechamento_historico_vinculo where unidade=? and mes=?)
                """,Boolean.class,unidade,mes.atDay(1)));
    }

    private void gravar(String unidade,Janela janela,Instant instante,String assinatura,String inventario,String indicadores) {
        jdbc.update("""
                insert into tb_observacao_historico_mensal(unidade,mes,fase,observado_em,assinatura,inventario,indicadores)
                values (?,?,?,?,?,?,?)
                """,unidade,janela.mes().atDay(1),janela.antes()?"PRE":"FECHADO",
                OffsetDateTime.ofInstant(instante.truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC),assinatura,inventario,indicadores);
    }
}

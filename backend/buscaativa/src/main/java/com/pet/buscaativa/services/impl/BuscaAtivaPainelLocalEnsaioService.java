package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.UnidadePainelDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.PoliticaClassificacaoPaciente;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.ConsultasClinicasLocaisEnsaioService.Leitura;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Decisao;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Motivo;
import lombok.RequiredArgsConstructor;

/** Contratos experimentais de busca ativa/painel; sem rotas e sem efeitos clínicos.
 * A auditoria de visualizações e a ativação HTTP serão integradas no corte. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class BuscaAtivaPainelLocalEnsaioService {
    private final ConsultasClinicasLocaisEnsaioService consultas;
    private final EscopoUnidadeService escopo;
    private final Clock clock;
    private final HistoricoRiscoVinculoEnsaioService historico;

    public enum Acao { REVISAO_HISTORICA, VISITA_DOMICILIAR, PONTO_REFERENCIA, CONFERIR_REFERENCIA, ACOMPANHAMENTO_PRIORITARIO }
    public record Alerta(UUID idPublico, UUID vinculo, Integer versao, String nome, ClassificacaoRisco classificacaoRisco,
            Integer faltasConsecutivas, Boolean gatilhoVisitaAcionado, Acao acao, String alerta, String localBusca,
            String avisoAdicional) {}
    public record Busca(List<Alerta> alertas, long estadoNaoConfirmado, long selecaoIndeterminada) {}
    public record Distribuicao(long verdes,long amarelos,long vermelhos,long naoConfirmados,
            double percentualVerdes,double percentualAmarelos,double percentualVermelhos,double percentualNaoConfirmados) {}
    public record Prioritario(UUID idPublico,UUID vinculo,Integer versao,String nome,Integer idade,
            ClassificacaoRisco classificacaoRisco,Integer quantidadeFaltas,String acaoNecessaria) {}
    public enum SituacaoHistorica { SEM_COBERTURA, INDISPONIVEL, COMPROVADO }
    public record Evolucao(String mes,String rotulo,Long verdes,Long amarelos,Long vermelhos,boolean disponivel,
            SituacaoHistorica situacao) {
        public Evolucao(String mes,String rotulo,Long verdes,Long amarelos,Long vermelhos,boolean disponivel) {
            this(mes, rotulo, verdes, amarelos, vermelhos, disponivel,
                    disponivel ? SituacaoHistorica.COMPROVADO : SituacaoHistorica.INDISPONIVEL);
        }
    }
    /** Totais de fatos com origem comprovada dos vínculos ativos selecionados, não totais completos do universo clínico. */
    public record Atividade(long reservasIndividuaisComprovadas,long sessoesDistintasComprovadas,
            long presencasComprovadas,long faltasComprovadas,long vinculosComRevisao,
            long semPresencaRecenteConfirmada,long semPresencaRecenteIndeterminada) {}
    /** Ponto atual não é fechamento de mês nem prova retrospectiva. */
    public record PontoAtual(LocalDate data,Distribuicao distribuicao,long estadoNaoConfirmado) {}
    public record Painel(long totalPacientesAtivos,long estadoNaoConfirmado,Distribuicao distribuicaoClassificacao,
            List<Evolucao> evolucao,List<Prioritario> pacientesPrioritarios,List<UnidadePainelDTO> unidadesDisponiveis,
            boolean historicoDisponivel,Atividade atividade,PontoAtual pontoAtual) {}

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRES_NEW)
    Busca buscaAtivaSnapshot() {
        return buscaAtiva();
    }

    public Busca buscaAtiva() {
        var locais=consultas.lerUnidade();
        var alertas=locais.stream().filter(BuscaAtivaPainelLocalEnsaioService::ativo)
                .filter(l->l.calculo().busca().decisao()==Decisao.INCLUIR)
                .sorted(prioridade()).map(this::alerta).toList();
        return new Busca(alertas,locais.stream().filter(l->l.item().indicadores().statusPaciente()==null).count(),
                locais.stream().filter(BuscaAtivaPainelLocalEnsaioService::ativo)
                        .filter(l->l.calculo().busca().decisao()==Decisao.INDETERMINADA).count());
    }

    public Painel painel(int periodoMeses,String unidade,TipoAcompanhamento tipo,Boolean situacaoRua) {
        var locais=consultas.lerUnidade();
        var unidadeAtual=escopo.unidadePainel();
        if (unidade!=null && !unidade.isBlank() && !unidadeAtual.valor().equals(unidade))
            throw new ResourceNotFoundException("Unidade não encontrada.");
        var filtrados=locais.stream().filter(l->tipo==null || tipo==l.item().indicadores().tipoAcompanhamento())
                .filter(l->situacaoRua==null || situacaoRua==l.local().getPaciente().isSituacaoRua()).toList();
        var ativos=filtrados.stream().filter(BuscaAtivaPainelLocalEnsaioService::ativo).toList();
        long total=ativos.size();
        long verdes=contar(ativos,ClassificacaoRisco.VERDE),amarelos=contar(ativos,ClassificacaoRisco.AMARELO),
                vermelhos=contar(ativos,ClassificacaoRisco.VERMELHO),desconhecidos=total-verdes-amarelos-vermelhos;
        var distribuicao=new Distribuicao(verdes,amarelos,vermelhos,desconhecidos,
                percentual(verdes,total),percentual(amarelos,total),percentual(vermelhos,total),percentual(desconhecidos,total));
        var prioridades=ativos.stream().filter(l->l.item().indicadores().revisaoNecessaria()
                || l.item().indicadores().classificacaoRisco()==ClassificacaoRisco.AMARELO
                || l.calculo().busca().decisao()==Decisao.INCLUIR)
                .sorted(prioridade()).limit(5).map(this::prioritario).toList();
        int periodo=List.of(3,6,12).contains(periodoMeses)?periodoMeses:6;
        var atual=YearMonth.now(clock);
        var meses=historico.reconstruir(java.util.stream.IntStream.range(0, periodo)
                .mapToObj(i->atual.minusMonths(periodo-1L-i)).toList(),tipo,situacaoRua);
        long reservas=ativos.stream().flatMap(l->l.calculo().evidencias().agenda().stream())
                .map(a->a.getId()).distinct().count();
        long sessoes=ativos.stream().flatMap(l->l.calculo().evidencias().participacoes().stream())
                .map(p->p.getSessaoGrupo().getId()).distinct().count();
        var fatos=ativos.stream().flatMap(l->l.calculo().evidencias().diagnostico().eventosComOrigemLocal().stream()).toList();
        long presencas=fatos.stream().filter(LinhaDoTempoFrequenciasVinculoService.Evento::presente).count();
        var atividade=new Atividade(reservas,sessoes,presencas,fatos.size()-presencas,
                ativos.stream().filter(l->l.item().indicadores().revisaoNecessaria()).count(),
                ativos.stream().filter(l->Boolean.TRUE.equals(semPresencaRecente(l))).count(),
                ativos.stream().filter(l->semPresencaRecente(l)==null).count());
        long estadoNaoConfirmado=filtrados.stream().filter(l->l.item().indicadores().statusPaciente()==null).count();
        return new Painel(total,estadoNaoConfirmado,
                distribuicao,meses,prioridades,List.of(unidadeAtual),meses.stream().anyMatch(Evolucao::disponivel),atividade,
                new PontoAtual(LocalDate.now(clock),distribuicao,estadoNaoConfirmado));
    }

    private static boolean ativo(Leitura l) { return l.item().indicadores().statusPaciente()==StatusPaciente.ATIVO; }
    private static long contar(List<Leitura> ls,ClassificacaoRisco risco) {
        return ls.stream().filter(l->l.item().indicadores().classificacaoRisco()==risco).count();
    }
    private static double percentual(long n,long total) {return total==0?0:Math.round(n*1000.0/total)/10.0;}

    private Boolean semPresencaRecente(Leitura l) {
        var i=l.item().indicadores();
        if (!i.ultimaPresencaConclusiva() || i.tipoAcompanhamento()==null || !Boolean.FALSE.equals(i.indicadoresPendentes())) return null;
        if (i.dataUltimaPresenca()==null) return true;
        int limite=i.tipoAcompanhamento()==TipoAcompanhamento.INDIVIDUAL
                ?PoliticaClassificacaoPaciente.DIAS_AMARELO_INDIVIDUAL:PoliticaClassificacaoPaciente.DIAS_AMARELO_GRUPO;
        return ChronoUnit.DAYS.between(i.dataUltimaPresenca(),LocalDate.now(clock))>limite;
    }

    private static Comparator<Leitura> prioridade() {
        return Comparator.comparingInt((Leitura l)->l.item().indicadores().classificacaoRisco()==ClassificacaoRisco.VERMELHO?0:1)
                .thenComparing(l->l.item().indicadores().countFaltas(),Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(l->l.item().nome(),Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(l->l.item().idPublico());
    }

    private Alerta alerta(Leitura l) {
        var item=l.item();
        var i=item.indicadores();
        var p=l.local().getPaciente();
        Acao acao;
        String texto,local=null,aviso=null;
        if (l.calculo().busca().motivo()==Motivo.REVISAO_HISTORICA) {
            acao=Acao.REVISAO_HISTORICA; texto="Revisão do histórico necessária";
            aviso="Existem indicadores pendentes de revisão; a ação de visita não está confirmada.";
        } else if (p.isSituacaoRua()) {
            var e=p.getEndereco();
            local=e==null || e.getComplemento()==null || e.getComplemento().isBlank()?null:e.getComplemento();
            acao=local==null?Acao.CONFERIR_REFERENCIA:Acao.PONTO_REFERENCIA;
            texto=Boolean.TRUE.equals(i.gatilhoVisitaAcionado())?"Busca em ponto de referência necessária":"Busca em ponto de referência recomendada";
            if(local==null) aviso="Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.";
        } else {
            acao=Acao.VISITA_DOMICILIAR;
            texto=Boolean.TRUE.equals(i.gatilhoVisitaAcionado())?"Visita domiciliar necessária":"Visita domiciliar recomendada";
            local=endereco(p.getEndereco());
            if(local==null) aviso="Endereço não cadastrado — verificar com a equipe antes de acionar a visita.";
        }
        return new Alerta(item.idPublico(),item.vinculo(),item.versao(),item.nome(),i.classificacaoRisco(),
                i.countFaltas(),i.gatilhoVisitaAcionado(),acao,texto,local,aviso);
    }

    private Prioritario prioritario(Leitura l) {
        var item=l.item();
        var i=item.indicadores();
        String acao=l.calculo().busca().decisao()==Decisao.INCLUIR?alerta(l).alerta()
                :i.revisaoNecessaria()?"Revisão do histórico necessária":"Acompanhamento prioritário";
        var nascimento=l.local().getPaciente().getDataNascimento();
        Integer idade=nascimento==null?null:Period.between(nascimento,LocalDate.now(clock)).getYears();
        return new Prioritario(item.idPublico(),item.vinculo(),item.versao(),item.nome(),idade,
                i.classificacaoRisco(),i.countFaltas(),acao);
    }

    private static String endereco(Endereco e) {
        if(e==null)return null;
        String texto=Stream.of(e.getLogradouro(),e.getNumero(),e.getBairro(),e.getCidade(),e.getEstado())
                .filter(Objects::nonNull).map(String::trim).filter(s->!s.isEmpty())
                .collect(java.util.stream.Collectors.joining(", "));
        return texto.isEmpty()?null:texto;
    }
}

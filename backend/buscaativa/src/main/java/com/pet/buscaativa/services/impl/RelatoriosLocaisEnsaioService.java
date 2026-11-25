package com.pet.buscaativa.services.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.ExcelRelatorioService;
import com.pet.buscaativa.services.PdfRendererService;
import com.pet.buscaativa.services.exceptions.*;
import com.pet.buscaativa.services.impl.ConsultasClinicasLocaisEnsaioService.Leitura;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Fonte local dos dois relatórios operacionais existentes. Sem endpoint ou escrita clínica. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class RelatoriosLocaisEnsaioService {
    private final ConsultasClinicasLocaisEnsaioService consultas;
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final UsuarioRepository usuarios;
    private final PdfRendererService pdf;
    private final ExcelRelatorioService excel;
    private final EntityManager em;
    private final Clock clock;
    private static final DateTimeFormatter DATA=DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale PT=Locale.forLanguageTag("pt-BR");

    public record Busca(List<BuscaAtivaRelatorioDTO> dados, long revisaoOuSelecaoInconclusiva,
            String periodo,String profissional,String tipo) {}
    public record Grupos(List<FrequenciaGrupoRelatorioDTO> dados,long grupos,long sessoesPendentes,
            BigDecimal media,String periodo) {}

    public Busca buscaAtiva(LocalDate inicio,LocalDate fim,UUID profissionalId,TipoAcompanhamento tipo) {
        validar(inicio,fim);
        var contexto=atores.autorizarOperacional();
        String profissional="Todos os profissionais";
        if(profissionalId!=null) profissional=usuarios.findByIdPublico(profissionalId)
                .filter(u->AtoresEstadoClinicoLocalEnsaio.elegivel(u,contexto.unidade(),false))
                .map(Usuario::getNome).orElseThrow(()->new ResourceNotFoundException("Usuário não encontrado."));
        var dados=new ArrayList<BuscaAtivaRelatorioDTO>();
        long revisar=0;
        for(var l:consultas.lerUnidade()) {
            var i=l.item().indicadores();
            var v=l.local();
            var ref=v.getProfissionalReferencia();
            boolean referenciaValida=ref!=null && AtoresEstadoClinicoLocalEnsaio.elegivel(ref,contexto.unidade(),false);
            if(profissionalId!=null && (!referenciaValida || !profissionalId.equals(ref.getIdPublico()))) continue;
            if (tipo != null && i.tipoAcompanhamento() != null && tipo != i.tipoAcompanhamento()) continue;
            if (i.statusPaciente() != StatusPaciente.ATIVO) {
                if (i.statusPaciente() == null) revisar++;
                continue;
            }
            // Filtros legados referem-se à data da última presença, não à data da falta.
            if(i.ultimaPresencaConclusiva()) {
                var d=i.dataUltimaPresenca();
                if(inicio!=null && (d==null||d.isBefore(inicio)) || fim!=null && (d==null||d.isAfter(fim))) continue;
            }
            if(i.tipoAcompanhamento()==null || !Boolean.FALSE.equals(i.indicadoresPendentes()) || !i.ultimaPresencaConclusiva()
                    || l.calculo().busca().decisao()==ComparadorSelecaoBuscaAtivaVinculo.Decisao.INDETERMINADA) {
                revisar++; continue;
            }
            if(i.classificacaoRisco()!=ClassificacaoRisco.VERMELHO && !Boolean.TRUE.equals(i.gatilhoVisitaAcionado())) continue;
            if(i.countFaltas()==null || i.tipoAcompanhamento()==null) { revisar++; continue; }
            var p=v.getPaciente();
            dados.add(new BuscaAtivaRelatorioDTO(p.getNome(),p.getCns()==null||p.getCns().isBlank()?p.getCpf():p.getCns(),
                    i.dataUltimaPresenca(),i.countFaltas(),i.tipoAcompanhamento().name().replace('_',' '),
                    referenciaValida?ref.getNome():"Não informado",p.getTelefone()));
        }
        dados.sort(Comparator.comparing(BuscaAtivaRelatorioDTO::getNomeCompleto,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new Busca(List.copyOf(dados),revisar,periodo(inicio,fim),profissional,
                tipo==null?"Individual e grupo":tipo.name().replace('_',' '));
    }

    public Grupos frequenciaGrupos(LocalDate inicio,LocalDate fim,Long grupoId) {
        var contexto=atores.autorizarOperacional();
        fim=fim==null?LocalDate.now(clock):fim;
        inicio=inicio==null?fim.minusMonths(6):inicio;
        validar(inicio,fim);
        if(grupoId!=null && em.createQuery("select count(g) from GrupoTerapeutico g where g.id=:id and g.unidadeOrigemChave=:unidade",Long.class)
                .setParameter("id",grupoId).setParameter("unidade",contexto.unidade()).getSingleResult()==0)
            throw new ResourceNotFoundException("Grupo não encontrado.");
        var locais=consultas.lerUnidade().stream().collect(Collectors.toMap(l->l.local().getPaciente().getId(),l->l));
        String hql="select s from SessaoGrupo s join fetch s.grupo g where (s.unidadeOrigemChave=:unidade or g.unidadeOrigemChave=:unidade) and (s.status=:status or s.status is null)"
                +" and s.dataSessao between :inicio and :fim"+(grupoId==null?"":" and g.id=:grupo")
                +" order by s.dataSessao,s.horario,s.id";
        var query=em.createQuery(hql,SessaoGrupo.class).setParameter("unidade",contexto.unidade())
                .setParameter("status",StatusSessaoGrupo.REALIZADA).setParameter("inicio",inicio).setParameter("fim",fim);
        if(grupoId!=null) query.setParameter("grupo",grupoId);
        var dados=new ArrayList<FrequenciaGrupoRelatorioDTO>();
        var grupos=new HashSet<Long>();
        long pendentes=0;
        int presentes=0,ausentes=0;
        for(var s:query.getResultList()) {
            if(s.getStatus()!=StatusSessaoGrupo.REALIZADA || !contexto.unidade().equals(s.getUnidadeOrigemChave())
                    || !contexto.unidade().equals(s.getGrupo().getUnidadeOrigemChave())
                    || AvaliacaoProvenienciaClinica.origemSessao(s)!=AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                    || s.getParticipantes().stream().anyMatch(p->!fatoColetivoComprovado(p,locais))) {
                pendentes++; continue;
            }
            var ps=s.getParticipantes().stream().filter(p->p.getStatusPresenca()==StatusPresencaGrupo.PRESENTE)
                    .map(p->p.getPaciente().getNome()).sorted().toList();
            var fs=s.getParticipantes().stream().filter(p->p.getStatusPresenca()==StatusPresencaGrupo.FALTOU)
                    .map(p->p.getPaciente().getNome()).sorted().toList();
            var taxa=taxa(ps.size(),fs.size());
            dados.add(new FrequenciaGrupoRelatorioDTO(s.getDataSessao(),s.getGrupo().getTema(),
                    s.getResponsavelAtendimento().getNome(),ps.size(),fs.size(),ps,fs,taxa,percentual(taxa)));
            grupos.add(s.getGrupo().getId()); presentes+=ps.size(); ausentes+=fs.size();
        }
        return new Grupos(List.copyOf(dados),grupos.size(),pendentes,taxa(presentes,ausentes),periodo(inicio,fim));
    }

    private boolean fatoColetivoComprovado(SessaoGrupoParticipante p,Map<Long,Leitura> locais) {
        if(p.getPaciente()==null || !AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca())) return false;
        var l=locais.get(p.getPaciente().getId());
        return l!=null && l.calculo().evidencias().diagnostico().eventosComOrigemLocal().stream()
                .anyMatch(f->f.fonte()==LinhaDoTempoFrequenciasVinculoService.Fonte.SESSAO_GRUPO
                        && f.registroId().equals(p.getId()) && f.presente()==(p.getStatusPresenca()==StatusPresencaGrupo.PRESENTE));
    }

    public byte[] exportarBusca(LocalDate inicio,LocalDate fim,UUID prof,TipoAcompanhamento tipo,boolean xlsx) {
        var b=buscaAtiva(inicio,fim,prof,tipo);
        // O template anterior representa null como zero; nunca encaminhar valores inconclusivos.
        String periodo=b.periodo()+" — critérios confirmados; revisar: "+b.revisaoOuSelecaoInconclusiva();
        if(xlsx) return excel.gerarBuscaAtiva(b.dados(),periodo,b.profissional(),b.tipo());
        var parametros=parametros(periodo);
        parametros.put("PROFISSIONAL",b.profissional()); parametros.put("TIPO_ACOMPANHAMENTO",b.tipo());
        parametros.put("TOTAL",b.dados().size()); parametros.put("pacientes",b.dados());
        return pdf.renderizar("relatorio-busca-ativa",parametros);
    }

    public byte[] exportarGrupos(LocalDate inicio,LocalDate fim,Long grupo,boolean xlsx) {
        var g=frequenciaGrupos(inicio,fim,grupo);
        // Não esconder uma sessão local inconclusiva em um relatório que não possui coluna de revisão.
        if(g.sessoesPendentes()>0) throw new ConflictException("Há sessões locais pendentes de conferência; exportação integral indisponível.");
        if(xlsx) return excel.gerarFrequenciaGrupos(g.dados(),g.periodo(),g.media(),g.grupos());
        var parametros=parametros(g.periodo());
        parametros.put("SESSOES",g.dados().size()); parametros.put("GRUPOS",g.grupos());
        parametros.put("PRESENCA_MEDIA",percentual(g.media())); parametros.put("sessoes",g.dados());
        return pdf.renderizar("relatorio-frequencia-grupos",parametros);
    }

    private Map<String,Object> parametros(String periodo) {
        var m=new HashMap<String,Object>();
        m.put("PERIODO",periodo); m.put("GERADO_EM",LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")));
        return m;
    }
    private static void validar(LocalDate inicio,LocalDate fim) {
        if(inicio!=null && fim!=null && inicio.isAfter(fim)) throw new ValidationException("Período inválido.");
    }
    private static String periodo(LocalDate inicio,LocalDate fim) {
        if(inicio==null && fim==null)return "Todo o período";
        return (inicio==null?"Início":inicio.format(DATA))+" a "+(fim==null?"Hoje":fim.format(DATA));
    }
    private static BigDecimal taxa(int p,int f) {
        return p+f==0?BigDecimal.ZERO.setScale(1):BigDecimal.valueOf(p).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf((long)p+f),1,RoundingMode.HALF_UP);
    }
    private static String percentual(BigDecimal t) {return String.format(PT,"%.1f%%",t);}
}

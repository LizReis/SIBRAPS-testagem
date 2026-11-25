package com.pet.buscaativa.services.impl;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;
import com.pet.buscaativa.entities.dto.ProximoAgendamentoDTO;
import com.pet.buscaativa.entities.dto.UsuarioReferenciaDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Motivo;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Leitura clínica experimental por vínculo, sem referências ao cache global.
 * Nenhuma rota ou escrita; snapshot serializável por consulta. DTOs não
 * carregam entidades, comparações globais, IDs de outros vínculos ou contagens
 * de fatos de origem desconhecida.
 */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class ConsultasClinicasLocaisEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EvidenciaStatusVinculo evidencia;
    private final CalculoIndicadoresVinculo calculo;
    private final DtosAssistenciaisLocaisEnsaioService dtos;
    private final HistoricoPacienteRepository historicos;
    private final HistoricoFrequenciaLocalEnsaio historicoFrequencia;
    private final EntityManager em;
    private final Clock clock;
    private final ModeloClinico modelo;

    public record Indicadores(StatusPaciente statusPaciente, TipoAcompanhamento tipoAcompanhamento,
            Integer countFaltas, LocalDate dataUltimaPresenca, boolean ultimaPresencaConclusiva,
            ClassificacaoRisco classificacaoRisco, Boolean gatilhoVisitaAcionado,
            Boolean indicadoresPendentes, boolean revisaoNecessaria, Set<Motivo> motivos) {}

    public record Item(UUID idPublico, UUID vinculo, Integer versao, Integer versaoCadastro, String unidade, String nome,
            String cpf, String cns, LocalDate dataNascimento, Indicadores indicadores) {}
    public record Pagina(List<Item> pacientes, int paginaAtual, int tamanhoPagina, long totalRegistros, int totalPaginas) {}

    public record EnderecoLeitura(String cidade, String estado, String bairro, String logradouro,
            String numero, String complemento, String cep) {}
    public record Cadastro(String nomeMae, LocalDate dataNascimento, SexoEnum sexo, RacaCorEnum racacor,
            String telefone, EnderecoLeitura endereco, boolean situacaoRua, Long usfReferenciaId,
            String nomeUsfReferencia, CapsEnum capsReferencia) {}
    public record Detalhe(Item paciente, Cadastro cadastro, UsuarioReferenciaDTO profissionalReferencia,
            boolean referenciaInconclusiva, ProximoAgendamentoDTO proximoAgendamento) {}
    public record Participacao(Long sessao, Long grupo, String tema, LocalDate data, LocalTime hora,
            StatusSessaoGrupo status, StatusPresencaGrupo frequencia) {}
    public record Evento(Long id, TipoEventoHistoricoPaciente tipo, SituacaoAtendimento situacao,
            LocalDateTime ocorridoEm, String descricao, Long agendamentoId, Long sessaoId,
            String nomeResponsavel, String unidadeOrigem, Integer numeroFaltaConsecutiva) {}
    public record Prontuario(Item paciente, List<AgendamentoDTO> atendimentos, List<Participacao> sessoes,
            List<Evento> historico, long presencasComprovadas, long faltasComprovadas,
            boolean historicoLocalRequerConferencia) {}

    record Leitura(VinculoAssistencial local, CalculoIndicadoresVinculo.Resultado calculo, Item item) {}
    private record ChaveFato(LinhaDoTempoFrequenciasVinculoService.Fonte fonte, Long id) {}

    public Pagina pesquisar(String q, ClassificacaoRisco risco, StatusPaciente status,
            TipoAcompanhamento tipo, int page, int size) {
        var contexto = atores.autorizarOperacional();
        int pagina = Math.max(0,page), tamanho = Math.min(50,Math.max(1,size));
        var termo = q==null?"":q.trim();
        var digitos = termo.replaceAll("\\D","");
        if (!termo.isEmpty() && ((!digitos.isEmpty() && digitos.length()!=11 && digitos.length()!=15)
                || (digitos.isEmpty() && termo.length()<3)))
            throw new ValidationException("Busca deve ter ao menos 3 letras, 11 dígitos de CPF ou 15 de CNS.");
        // A pesquisa e o conjunto de candidatos começam pelo vínculo, não
        // por busca global de documento. locate trata %/_ como caracteres.
        boolean porDocumento = digitos.length() == 11 || digitos.length() == 15;
        var identidade = modelo.operacional() && porDocumento
                ? (digitos.length() == 11 ? modelo.porCpf(digitos) : modelo.porCns(digitos))
                : java.util.Optional.<Paciente>empty();
        if (modelo.operacional() && porDocumento && identidade.isEmpty())
            return new Pagina(List.of(), pagina, tamanho, 0, 0);

        String filtro = termo.isEmpty() ? "" : identidade.isPresent() ? " and v.paciente.id=:identidade"
                : digitos.length() == 11 ? " and v.paciente.cpf=:termo"
                : digitos.length() == 15 ? " and v.paciente.cns=:termo"
                : " and locate(:termo,lower(v.paciente.nome))>0";
        var consulta = em.createQuery("select v from VinculoAssistencial v join fetch v.paciente where v.unidadeChave=:unidade"
                + filtro + " order by v.id", VinculoAssistencial.class).setParameter("unidade", contexto.unidade());
        if (identidade.isPresent()) consulta.setParameter("identidade", identidade.orElseThrow().getId());
        else if (!termo.isEmpty()) consulta.setParameter("termo", digitos.isEmpty() ? termo.toLowerCase(Locale.ROOT) : digitos);
        var itens = consulta.getResultList().stream().filter(ProjecaoClinicaVinculoService::chaveCanonica)
                .map(this::ler).map(Leitura::item)
                .filter(i->risco==null || risco==i.indicadores().classificacaoRisco())
                .filter(i->status==null || status==i.indicadores().statusPaciente())
                .filter(i->tipo==null || tipo==i.indicadores().tipoAcompanhamento())
                .sorted(Comparator.comparing((Item i)->i.indicadores().classificacaoRisco(),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(i->i.indicadores().countFaltas(),Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Item::nome,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Item::idPublico)).toList();
        long total=itens.size();
        return new Pagina(itens.stream().skip((long)pagina*tamanho).limit(tamanho).toList(),pagina,tamanho,
                total,(int)((total+tamanho-1)/tamanho));
    }

    public Detalhe detalhe(UUID paciente) {
        atores.autorizarOperacional();
        var l=ler(exigir(paciente));
        var v=l.local();
        var p=v.getPaciente();
        var e=p.getEndereco();
        var endereco=e==null?null:new EnderecoLeitura(e.getCidade(),e.getEstado(),e.getBairro(),e.getLogradouro(),
                e.getNumero(),e.getComplemento(),e.getCep());
        var cadastro=new Cadastro(p.getNomeMae(),p.getDataNascimento(),p.getSexo(),p.getRacacor(),p.getTelefone(),
                endereco,p.isSituacaoRua(),p.getUsfReferencia()==null?null:p.getUsfReferencia().getId(),
                p.getUsfReferencia()==null?null:p.getUsfReferencia().getNomeUsf(),p.getCapsReferencia());
        var ref=v.getProfissionalReferencia();
        boolean refInconclusiva=ref!=null && !AtoresEstadoClinicoLocalEnsaio.elegivel(ref,v.getUnidadeChave(),false);
        var proximo=l.calculo().evidencias().agenda().stream()
                .filter(a->a.getDataAgendamento()!=null && a.getHoraAtendimento()!=null
                        && !a.getDataAgendamento().isBefore(LocalDate.now(clock))
                        && Boolean.TRUE.equals(a.getOcupaVaga())
                        && (a.getSituacaoAtendimento()==SituacaoAtendimento.AGENDADO
                            || a.getSituacaoAtendimento()==SituacaoAtendimento.REMARCADO))
                .min(Comparator.comparing(Agendamento::getDataAgendamento).thenComparing(Agendamento::getHoraAtendimento)
                        .thenComparing(Agendamento::getId))
                .map(a->new ProximoAgendamentoDTO(a.getId(),a.getDataAgendamento(),a.getHoraAtendimento(),
                        a.getSituacaoAtendimento())).orElse(null);
        return new Detalhe(l.item(),cadastro,ref==null||refInconclusiva?null:new UsuarioReferenciaDTO(ref),
                refInconclusiva,proximo);
    }

    public Prontuario prontuario(UUID paciente) {
        atores.autorizarOperacional();
        var l=ler(exigir(paciente));
        var v=l.local();
        var ev=l.calculo().evidencias();
        var agenda=ev.agenda().stream().sorted(Comparator.comparing(Agendamento::getDataAgendamento,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Agendamento::getHoraAtendimento,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Agendamento::getId))
                .map(a->dtos.atendimento(a.getId())).toList();
        // Somente a participação do paciente consultado, não a lista de colegas.
        var grupos=ev.participacoes().stream().map(p->{
            var s=p.getSessaoGrupo();
            return new Participacao(s.getId(),s.getGrupo().getId(),s.getGrupo().getTema(),s.getDataSessao(),
                    s.getHorario(),s.getStatus(),s.getStatus()==StatusSessaoGrupo.REALIZADA
                            && AvaliacaoProvenienciaClinica.frequenciaRegistrada(p.getStatusPresenca())
                            ?p.getStatusPresenca():null);
        }).sorted(Comparator.comparing(Participacao::data,Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Participacao::hora,Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Participacao::sessao)).toList();

        var reservas=ev.agenda().stream().collect(Collectors.toMap(Agendamento::getId,a->a));
        var participantes=ev.participacoes().stream().collect(Collectors.groupingBy(p->p.getSessaoGrupo().getId()));
        var fatos=ev.diagnostico().eventosComOrigemLocal();
        var numeros=numeros(l);
        var porFato=fatos.stream().collect(Collectors.toMap(f->new ChaveFato(f.fonte(),f.registroId()),f->f));
        var eventos=new ArrayList<Evento>();
        boolean revisar=l.item().indicadores().revisaoNecessaria();
        var registros=historicos.findLocaisComOrigemComprovada(paciente,v.getUnidadeChave());
        for (var h:registros) {
            try {
                autorizacao.validarHistoricoLocal(h);
                Integer numero=null;
                if (h.getAgendamento()!=null) {
                    var a=reservas.get(h.getAgendamento().getId());
                    if (a==null) { revisar=true; continue; }
                    if (h.getTipo()==TipoEventoHistoricoPaciente.FALTA || h.getTipo()==TipoEventoHistoricoPaciente.PRESENCA
                            || h.getTipo()==TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO) {
                        var chave=new ChaveFato(LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL,a.getId());
                        var f=porFato.get(chave);
                        var coerente=historicoFrequencia.individual(a,v,a.getFrequenciaAssistencial());
                        if (f==null || coerente==null || !h.getId().equals(coerente.getId())
                                || !Objects.equals(f.ocorridoEm(),h.getOcorridoEm())) { revisar=true; continue; }
                        numero=numeros.get(chave);
                    }
                } else if (h.getSessaoGrupo()!=null) {
                    var ps=participantes.getOrDefault(h.getSessaoGrupo().getId(),List.of());
                    if (ps.size()!=1) { revisar=true; continue; }
                    var p=ps.getFirst();
                    var chave=new ChaveFato(LinhaDoTempoFrequenciasVinculoService.Fonte.SESSAO_GRUPO,p.getId());
                    var f=porFato.get(chave);
                    if (f==null) { revisar=true; continue; }
                    var coerente=historicoFrequencia.coletivo(p,v,FrequenciaAssistencial.valueOf(p.getStatusPresenca().name()));
                    if (coerente==null || !h.getId().equals(coerente.getId())
                            || !Objects.equals(f.ocorridoEm(),h.getOcorridoEm())) { revisar=true; continue; }
                    numero=numeros.get(chave);
                } else if (!AvaliacaoProvenienciaClinica.acaoLocalSemFonteRegistrada(h)) { revisar=true; continue; }
                eventos.add(new Evento(h.getId(),h.getTipo(),h.getSituacaoAtendimento(),h.getOcorridoEm(),h.getDescricao(),
                        h.getAgendamento()==null?null:h.getAgendamento().getId(),
                        h.getSessaoGrupo()==null?null:h.getSessaoGrupo().getId(),
                        h.getProfissional()==null?null:h.getProfissional().getNome(),v.getUnidadeChave(),numero));
            } catch (ResourceNotFoundException | ConflictException incoerente) {
                revisar=true;
            }
        }
        // Não confundir totais de fatos comprovados com totais completos do prontuário.
        long presentes=fatos.stream().filter(LinhaDoTempoFrequenciasVinculoService.Evento::presente).count();
        return new Prontuario(l.item(),agenda,grupos,List.copyOf(eventos),presentes,fatos.size()-presentes,revisar);
    }

    private Map<ChaveFato,Integer> numeros(Leitura l) {
        if (!l.calculo().frequencias().podeAvaliarSequencia()) return Map.of();
        var fatos=l.calculo().evidencias().diagnostico().eventosComOrigemLocal();
        for (int i=1;i<fatos.size();i++)
            if (!fatos.get(i-1).presente() && !fatos.get(i).presente()
                    && fatos.get(i-1).ocorridoEm().equals(fatos.get(i).ocorridoEm())) return Map.of();
        var resultado=new HashMap<ChaveFato,Integer>();
        int n=0;
        for (var f:fatos) {
            n=f.presente()?0:n+1;
            if (!f.presente()) resultado.put(new ChaveFato(f.fonte(),f.registroId()),n);
        }
        return resultado;
    }

    private VinculoAssistencial exigir(UUID paciente) {
        var v=autorizacao.exigirVinculoLocal(paciente);
        if (!ProjecaoClinicaVinculoService.chaveCanonica(v)) throw new ResourceNotFoundException("Paciente não encontrado.");
        return v;
    }

    /** Uso interno na mesma transação de leitura do painel/alertas.
     * Unidade exclusivamente derivada do ator; nunca aceita unidade do cliente.
     * Entidades deste resultado não atravessam o contrato público. */
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<Leitura> lerUnidade() {
        var contexto=atores.autorizarOperacional();
        return em.createQuery("select v from VinculoAssistencial v join fetch v.paciente where v.unidadeChave=:unidade order by v.id",
                VinculoAssistencial.class).setParameter("unidade",contexto.unidade()).getResultList().stream()
                .filter(ProjecaoClinicaVinculoService::chaveCanonica).map(this::ler).toList();
    }

    private Leitura ler(VinculoAssistencial v) {
        var c=calculo.calcular(v,LocalDate.now(clock),evidencia.analisar(v),v.getIndicadoresPendentes());
        var motivos=EnumSet.noneOf(Motivo.class);
        motivos.addAll(c.status().motivos()); motivos.addAll(c.tipo().motivos());
        motivos.addAll(c.pendencia().motivos()); motivos.addAll(c.faltas().motivos());
        motivos.addAll(c.ultimaPresenca().motivos()); motivos.addAll(c.risco().motivos()); motivos.addAll(c.visita().motivos());
        if (Boolean.TRUE.equals(v.getIndicadoresPendentes())) motivos.add(Motivo.INDICADORES_PENDENTES);
        var status=c.status().valor().orElse(null);
        var indicadores=new Indicadores(status,c.tipo().valor().orElse(null),c.faltas().valor().orElse(null),
                c.ultimaPresenca().valor().orElse(null),c.ultimaPresenca().conclusiva(),
                c.risco().valor().orElse(null),status==null?null:c.visita().valor().orElse(null),
                v.getIndicadoresPendentes(),!motivos.isEmpty(),Set.copyOf(motivos));
        var p=v.getPaciente();
        return new Leitura(v,c,new Item(p.getIdPublico(),v.getIdPublico(),v.getVersion(),p.getVersion(),v.getUnidadeChave(),
                p.getNome(),p.getCpf(),p.getCns(),p.getDataNascimento(),indicadores));
    }
}

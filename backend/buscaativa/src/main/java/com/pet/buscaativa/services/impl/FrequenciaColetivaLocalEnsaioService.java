package com.pet.buscaativa.services.impl;

import static com.pet.buscaativa.entities.enums.StatusPresencaGrupo.FALTOU;
import static com.pet.buscaativa.entities.enums.StatusPresencaGrupo.NAO_REGISTRADA;
import static com.pet.buscaativa.entities.enums.StatusPresencaGrupo.PRESENTE;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.AuditoriaCorrecaoFrequencia;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Descoberta;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.PoliticaTemporalFrequencia;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class FrequenciaColetivaLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final GrupoTerapeuticoServiceImpl guardasGrupo;
    private final HistoricoFrequenciaLocalEnsaio historico;
    private final PersistenciaIndicadoresVinculoEnsaioService indicadores;
    private final PoliticaJustificativaCorrecao justificativas;
    private final PoliticaTemporalFrequencia temporal;
    private final EscopoUnidadeService escopo;
    private final EntityManager em;
    private final Clock clock;

    record Sessao(Long id, Long grupo, UUID coordenador, UUID responsavel,
            Integer versaoGrupo, Integer versaoSessao, StatusSessaoGrupo status) {}
    record Participante(Long id, UUID paciente, StatusPresencaGrupo frequencia) {}
    record Composicao(Sessao sessao, List<Participante> participantes) {}
    record Mudanca(SessaoGrupoParticipante participante, StatusPresencaGrupo antes,
            StatusPresencaGrupo depois, HistoricoPaciente historico) {}

    @Transactional
    public int confirmar(Long sessao, int versao, Map<UUID, StatusPresencaGrupo> frequencias) {
        return executar(sessao, versao, frequencias, null, false);
    }

    @Transactional
    public int corrigir(Long sessao, int versao, Map<UUID, StatusPresencaGrupo> frequencias, String justificativa) {
        return executar(sessao, versao, frequencias, justificativa, true);
    }

    private int executar(Long id, int versao, Map<UUID, StatusPresencaGrupo> entradas,
            String justificativa, boolean correcao) {
        if (entradas == null || correcao && entradas.isEmpty()
                || entradas.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() != PRESENTE && e.getValue() != FALTOU))
            throw new ValidationException("Informe frequências válidas da sessão.");
        var frequencias = Map.copyOf(entradas);
        var contexto = atores.autorizar(false);
        var recursos = coordenacao.bloquear(() -> descobrir(id, contexto), contexto, null);
        var ator = recursos.atores().ator();
        var sessao = recursos.sessoes().get(id);
        var coordenador = sessao.getGrupo().getCoordenador();
        var responsavel = correcao ? sessao.getResponsavelAtendimento() : coordenador;
        if (sessao.getStatus() != (correcao ? StatusSessaoGrupo.REALIZADA : StatusSessaoGrupo.AGENDADA)
                || AvaliacaoProvenienciaClinica.origemSessao(sessao) != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA
                || responsavel == null)
            throw new ConflictException("A sessão não tem estado ou proveniência compatível.");
        if (!AtoresEstadoClinicoLocalEnsaio.elegivel(coordenador, contexto.unidade(), false)
                || ator.getTipoUsuario() != TipoUsuario.ADMINISTRADOR && !ator.getId().equals(responsavel.getId()))
            throw new AccessDeniedException("Usuário sem autorização para esta sessão.");
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, sessao.getVersion());
        temporal.validarSessao(sessao, correcao);
        if (!recursos.vinculos().keySet().containsAll(frequencias.keySet()))
            throw new ValidationException("Uma frequência informada não pertence à sessão.");
        if (!correcao) guardasGrupo.validarRealizacaoColetiva(sessao);

        var mudancas = new ArrayList<Mudanca>();
        for (var registro : recursos.relacoes().participantes().stream()
                .sorted(Comparator.comparing(Participante::paciente)).toList()) {
            var p = em.find(SessaoGrupoParticipante.class, registro.id());
            if (p == null) throw CoordenacaoEscritaClinicaEnsaio.mudou();
            em.refresh(p);
            if (!id.equals(p.getSessaoGrupo().getId()) || !registro.paciente().equals(p.getPaciente().getIdPublico())
                    || registro.frequencia() != p.getStatusPresenca()) throw CoordenacaoEscritaClinicaEnsaio.mudou();
            var destino = frequencias.getOrDefault(registro.paciente(), correcao ? p.getStatusPresenca() : FALTOU);
            if (correcao && p.getStatusPresenca() == destino) continue;
            if (correcao && (p.getStatusPresenca() != PRESENTE && p.getStatusPresenca() != FALTOU))
                throw new ConflictException("Frequência anterior não comprovada.");
            var local = recursos.vinculos().get(registro.paciente());
            if (destino == PRESENTE) guardasGrupo.validarPresencaColetiva(p.getPaciente(), sessao, local);
            var anterior = correcao ? FrequenciaAssistencial.valueOf(p.getStatusPresenca().name())
                    : FrequenciaAssistencial.NAO_REGISTRADA;
            mudancas.add(new Mudanca(p, p.getStatusPresenca(), destino, historico.coletivo(p, local, anterior)));
        }
        if (correcao && mudancas.isEmpty()) return sessao.getVersion();
        String motivo = correcao ? justificativas.validar(justificativa) : null;
        if (correcao) em.lock(sessao, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        else {
            sessao.setMotivoCancelamento(null);
            sessao.confirmarRealizacao(responsavel);
        }
        var empatesAnteriores = new java.util.HashSet<UUID>();
        if (correcao) for (var m : mudancas) {
            var paciente = m.participante().getPaciente().getIdPublico();
            if (indicadores.empateConhecidoAntesDaCorrecao(recursos.vinculos().get(paciente)))
                empatesAnteriores.add(paciente);
        }
        mudancas.forEach(m -> m.participante().setStatusPresenca(m.depois()));
        em.flush();
        var avaliacoes = mudancas.stream().map(m -> {
            var paciente = m.participante().getPaciente().getIdPublico();
            return indicadores.avaliarAposCorrecao(recursos.vinculos().get(paciente), empatesAnteriores.contains(paciente));
        }).toList();
        // Fatos válidos não dependem da conclusão dos indicadores: o mesmo
        // contrato conservador da frequência individual preserva o cache
        // inconclusivo e sinaliza pendência quando cabível. Falha de validação
        // factual ou persistência ainda reverte toda a sessão.
        avaliacoes.forEach(a -> indicadores.aplicar(a, ator));

        var instante = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var unidade = escopo.unidadePainel().valor();
        for (var m : mudancas) {
            var p = m.participante();
            historico.registrarGrupo(p, m.historico(), correcao
                    ? "Frequência do grupo corrigida no ensaio." : "Frequência do grupo confirmada no ensaio.");
            if (correcao)
                em.persist(new AuditoriaCorrecaoFrequencia(null, sessao, p, p.getPaciente(), ator, responsavel, unidade,
                        FrequenciaAssistencial.valueOf(m.antes().name()), FrequenciaAssistencial.valueOf(m.depois().name()),
                        motivo, instante, versao, sessao.getVersion()));
        }
        em.flush();
        avaliacoes.forEach(a -> historico.renumerar(a.vinculo()));
        return sessao.getVersion();
    }

    private Descoberta<Composicao> descobrir(Long id, AtoresEstadoClinicoLocalEnsaio.Contexto contexto) {
        if (id == null) throw new ResourceNotFoundException("Sessão não encontrada.");
        var sessao = em.createQuery("""
                select new com.pet.buscaativa.services.impl.FrequenciaColetivaLocalEnsaioService$Sessao(
                    s.id, g.id, g.coordenador.idPublico, r.idPublico, g.version, s.version, s.status)
                from SessaoGrupo s join s.grupo g left join s.responsavelAtendimento r
                where s.id = :id and s.unidadeOrigemChave = :unidade and g.unidadeOrigemChave = :unidade
                """, Sessao.class).setParameter("id", id).setParameter("unidade", contexto.unidade())
                .getResultList().stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Sessão não encontrada."));
        var participantes = em.createQuery("""
                select new com.pet.buscaativa.services.impl.FrequenciaColetivaLocalEnsaioService$Participante(
                    p.id, p.paciente.idPublico, p.statusPresenca)
                from SessaoGrupoParticipante p where p.sessaoGrupo.id = :id order by p.id
                """, Participante.class).setParameter("id", id).getResultList();
        var pacientes = participantes.stream().map(Participante::paciente).toList();
        var locais = pacientes.isEmpty() ? List.<CoordenacaoEscritaClinicaEnsaio.IdentidadeVinculo>of()
                : coordenacao.identificar(pacientes, contexto.unidade(), true);
        return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, pacientes, contexto, null,
                sessao.responsavel() == null ? List.of(sessao.coordenador()) : List.of(sessao.coordenador(), sessao.responsavel()),
                List.of(sessao.grupo()), List.of(sessao.id()), List.of(), new Composicao(sessao, List.copyOf(participantes)));
    }
}

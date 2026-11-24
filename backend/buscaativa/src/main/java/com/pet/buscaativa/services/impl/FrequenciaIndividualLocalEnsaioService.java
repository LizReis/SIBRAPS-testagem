package com.pet.buscaativa.services.impl;

import static com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU;
import static com.pet.buscaativa.entities.enums.FrequenciaAssistencial.NAO_REGISTRADA;
import static com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.AuditoriaCorrecaoFrequencia;
import com.pet.buscaativa.entities.AuditoriaRegularizacaoCancelamento;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.migration.PlanoCoordenacaoEscritaClinica.Descoberta;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.AuditoriaCorrecaoRepository;
import com.pet.buscaativa.repositories.AuditoriaRegularizacaoRepository;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.AuditoriaRegularizacaoService;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.PoliticaTemporalFrequencia;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
public class FrequenciaIndividualLocalEnsaioService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final AgendamentoRepository agenda;
    private final AgendamentoServiceImpl guardasAgenda;
    private final AuditoriaCorrecaoRepository correcoes;
    private final AuditoriaRegularizacaoRepository regularizacoes;
    private final AuditoriaRegularizacaoService auditoriaRegularizacao;
    private final HistoricoFrequenciaLocalEnsaio historico;
    private final HistoricoPacienteService historicoReservas;
    private final PersistenciaIndicadoresVinculoEnsaioService indicadores;
    private final PoliticaJustificativaCorrecao justificativas;
    private final PoliticaTemporalFrequencia temporal;
    private final EscopoUnidadeService escopo;
    private final EntityManager em;
    private final Clock clock;

    public record Resultado(Long atendimento, Integer versaoFato,
            PersistenciaIndicadoresVinculoEnsaioService.Resultado indicadores) {}

    record Fato(Long id, UUID paciente, UUID profissional, Long original, String unidade, Integer versao) {}
    private enum Operacao { REGISTRAR, CORRIGIR, REGULARIZAR }

    @Transactional
    public Resultado registrar(Long id, int versao, FrequenciaAssistencial frequencia) {
        return frequencia(id, versao, frequencia, null, Operacao.REGISTRAR);
    }

    @Transactional
    public Resultado corrigir(Long id, int versao, FrequenciaAssistencial frequencia, String justificativa) {
        return frequencia(id, versao, frequencia, justificativa, Operacao.CORRIGIR);
    }

    @Transactional
    public Resultado regularizarCancelamento(Long id, int versao, FrequenciaAssistencial frequencia, String justificativa) {
        return frequencia(id, versao, frequencia, justificativa, Operacao.REGULARIZAR);
    }

    private Resultado frequencia(Long id, int versao, FrequenciaAssistencial destino, String justificativa, Operacao operacao) {
        if (destino != PRESENTE && destino != FALTOU) throw new ValidationException("Informe presença ou falta.");
        var contexto = atores.autorizar(false);
        var bloqueados = coordenacao.bloquear(() -> descobrir(id, contexto), contexto, null);
        var fato = bloqueados.agenda().get(id);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(fato.getPaciente().getIdPublico());
        autorizar(fato, ator, contexto.unidade());
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, fato.getVersion());

        var antes = fato.getFrequenciaAssistencial();
        boolean cancelado = fato.getSituacaoReserva() == SituacaoReserva.CANCELADA;
        boolean correcao = operacao == Operacao.CORRIGIR;
        boolean regularizacao = operacao == Operacao.REGULARIZAR;
        if (regularizacao) {
            if (!cancelado || antes != NAO_REGISTRADA || agenda.existsByAgendamentoOriginal_Id(id)
                    || correcoes.existsByAgendamento_Id(id)
                    || regularizacoes.countByAgendamento_IdAndTipoRegularizacao(id, "REGISTRO_FATO_ASSISTENCIAL") != 0)
                throw new ConflictException("Regularização exige cancelamento sem frequência prévia.");
        } else if (correcao) {
            if (antes == NAO_REGISTRADA || cancelado && !auditoriaRegularizacao.cadeiaFactualCoerente(fato, escopo.unidadePainel().valor()))
                throw new ConflictException("Correção exige frequência anterior comprovada.");
        } else if (cancelado || fato.getSituacaoReserva() == SituacaoReserva.SUBSTITUIDA
                || antes != NAO_REGISTRADA && antes != destino) {
            throw new ConflictException("O atendimento exige o comando específico de correção ou regularização.");
        }
        temporal.validarAgendamento(fato, correcao || regularizacao);
        if (antes == destino) {
            return new Resultado(id, fato.getVersion(), new PersistenciaIndicadoresVinculoEnsaioService.Resultado(
                    PersistenciaIndicadoresVinculoEnsaioService.Situacao.SEM_ALTERACAO, false, local.getVersion()));
        }
        String motivo = correcao || regularizacao ? justificativas.validar(justificativa) : null;
        var evento = historico.individual(fato, local, antes);
        if (cancelado && correcao && !LocalDateTime.of(fato.getDataAgendamento(), fato.getHoraAtendimento())
                .equals(evento.getOcorridoEm()))
            throw new ConflictException("Histórico da regularização pendente de conferência.");
        try {
            if (destino == PRESENTE) guardasAgenda.validarPresencaIndividual(fato, local);
            if (regularizacao || antes == FALTOU && destino == PRESENTE)
                guardasAgenda.validarConflitosDeHorario(fato.getUsuario(), fato.getPaciente(),
                        fato.getDataAgendamento(), fato.getHoraAtendimento(), id);
        } catch (ConflictException conflito) {
            throw new ConflictException("Conflito assistencial no horário informado.");
        }

        if (regularizacao) fato.registrarFatoEmReservaCancelada(destino);
        else fato.setSituacaoAtendimento(SituacaoAtendimento.valueOf(destino.name()));
        em.flush();
        var resultado = indicadores.aplicar(indicadores.avaliar(local), ator);
        historico.registrarIndividual(fato, evento, switch (operacao) {
                    case REGISTRAR -> "Frequência individual registrada no ensaio.";
                    case CORRIGIR -> "Frequência individual corrigida no ensaio.";
                    case REGULARIZAR -> "Frequência regularizada preservando a reserva cancelada.";
                });
        var agora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var unidadeAuditoria = escopo.unidadePainel().valor();
        if (correcao)
            em.persist(new AuditoriaCorrecaoFrequencia(fato, null, null, fato.getPaciente(), ator, fato.getUsuario(),
                    unidadeAuditoria, antes, destino, motivo, agora, versao, fato.getVersion()));
        if (regularizacao)
            em.persist(new AuditoriaRegularizacaoCancelamento(fato, ator, unidadeAuditoria,
                    "REGISTRO_FATO_ASSISTENCIAL", motivo, agora, versao));
        return new Resultado(id, fato.getVersion(), resultado);
    }

    @Transactional
    public Resultado remarcar(Long originalId, int versao, LocalDate data, LocalTime hora, TurnoEnum turno) {
        if (data == null || hora == null || turno == null) throw new ValidationException("Informe o horário da remarcação.");
        var contexto = atores.autorizar(false);
        var bloqueados = coordenacao.bloquear(() -> descobrir(originalId, contexto), contexto, null);
        var original = bloqueados.agenda().get(originalId);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(original.getPaciente().getIdPublico());
        autorizar(original, ator, contexto.unidade());
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, original.getVersion());
        if (data.isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar agendamento em data passada.");
        var antes = original.getSituacaoAtendimento();
        if (local.getStatusPaciente() != StatusPaciente.ATIVO || local.getTipoAcompanhamento() == null
                || local.getTipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO
                || original.getSituacaoReserva() != SituacaoReserva.AGENDADA
                        && original.getSituacaoReserva() != SituacaoReserva.REMARCADA
                || !List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.FALTOU).contains(antes)
                || agenda.existsByAgendamentoOriginal_Id(originalId)
                || original.getDataAgendamento() == null || original.getHoraAtendimento() == null
                || !LocalDateTime.of(original.getDataAgendamento(), original.getHoraAtendimento()).isBefore(LocalDateTime.of(data, hora)))
            throw new ConflictException("O atendimento não permite criar sucessor por remarcação.");
        guardasAgenda.validarVagaOperacional(original.getUsuario(), original.getPaciente(), data, turno, hora, null, local);

        var sucessor = new Agendamento();
        sucessor.setPaciente(original.getPaciente());
        sucessor.setUsuario(original.getUsuario());
        sucessor.setDataAgendamento(data);
        sucessor.setHoraAtendimento(hora);
        sucessor.setTurnoAgendamento(turno);
        sucessor.registrarUnidadeDeOrigem(sucessor.getUsuario());
        sucessor.setAgendamentoOriginal(original);
        sucessor.setRemarcacaoAposFalta(antes == SituacaoAtendimento.FALTOU);
        sucessor.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO);
        original.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        em.persist(sucessor);
        em.flush();
        var resultado = indicadores.aplicar(indicadores.avaliar(local), ator);
        historicoReservas.registrarAlteracaoDeAtendimento(original, antes);
        historicoReservas.registrarAgendamento(sucessor);
        return new Resultado(sucessor.getId(), sucessor.getVersion(), resultado);
    }

    private void autorizar(Agendamento a, com.pet.buscaativa.entities.Usuario ator, String unidade) {
        autorizacao.validarAgendamentoLocal(a);
        if (!AtoresEstadoClinicoLocalEnsaio.elegivel(a.getUsuario(), unidade, false)
                || ator.getTipoUsuario() != TipoUsuario.ADMINISTRADOR && !ator.getId().equals(a.getUsuario().getId()))
            throw new AccessDeniedException("Usuário sem autorização para este atendimento.");
    }

    private Descoberta<List<Fato>> descobrir(Long id, AtoresEstadoClinicoLocalEnsaio.Contexto contexto) {
        var cadeia = new ArrayList<Fato>();
        var visitados = new HashSet<Long>();
        for (Long atual = id; atual != null;) {
            if (!visitados.add(atual)) throw new ConflictException("Cadeia de remarcação inconclusiva.");
            var fato = em.createQuery("""
                    select new com.pet.buscaativa.services.impl.FrequenciaIndividualLocalEnsaioService$Fato(
                        a.id, a.paciente.idPublico, a.usuario.idPublico, original.id, a.unidadeOrigemChave, a.version)
                    from Agendamento a left join a.agendamentoOriginal original
                    where a.id = :id and a.unidadeOrigemChave = :unidade
                    """, Fato.class).setParameter("id", atual).setParameter("unidade", contexto.unidade())
                    .getResultList().stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Atendimento não encontrado."));
            if (!cadeia.isEmpty() && !cadeia.getFirst().paciente().equals(fato.paciente()))
                throw new ConflictException("Cadeia de remarcação inconclusiva.");
            cadeia.add(fato);
            atual = fato.original();
        }
        if (cadeia.isEmpty()) throw new ResourceNotFoundException("Atendimento não encontrado.");
        var pacientes = List.of(cadeia.getFirst().paciente());
        var locais = coordenacao.identificar(pacientes, contexto.unidade(), true);
        return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, pacientes, contexto, null,
                cadeia.stream().map(Fato::profissional).toList(), List.of(), List.of(),
                cadeia.stream().map(Fato::id).toList(), List.copyOf(cadeia));
    }
}

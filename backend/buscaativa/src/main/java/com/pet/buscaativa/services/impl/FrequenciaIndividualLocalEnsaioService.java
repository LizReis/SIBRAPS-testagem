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
    private enum Operacao { REGISTRAR, CORRIGIR, REGULARIZAR, PATCH }

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

    /** Adaptador de status clínico: decide registro/correção somente sob locks. */
    @Transactional
    public Resultado atualizarFrequencia(Long id, int versao, FrequenciaAssistencial destino, String justificativa) {
        return frequencia(id, versao, destino, justificativa, Operacao.PATCH);
    }

    private Resultado frequencia(Long id, int versao, FrequenciaAssistencial destino, String justificativa, Operacao operacao) {
        if (destino != PRESENTE && destino != FALTOU) throw new ValidationException("Informe presença ou falta.");
        var contexto = operacao == Operacao.PATCH ? atores.autorizarOperacional() : atores.autorizar(false);
        var bloqueados = coordenacao.bloquear(() -> descobrir(id, contexto), contexto, null);
        var fato = bloqueados.agenda().get(id);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(fato.getPaciente().getIdPublico());
        var antes = fato.getFrequenciaAssistencial();
        if (operacao == Operacao.PATCH && antes == NAO_REGISTRADA)
            autorizarReserva(fato, ator, contexto.unidade());
        else
            autorizar(fato, ator, contexto.unidade());
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, fato.getVersion());

        boolean cancelado = fato.getSituacaoReserva() == SituacaoReserva.CANCELADA;
        if (operacao == Operacao.PATCH) {
            if (cancelado) throw new ConflictException("Atendimento cancelado exige regularização específica.");
            operacao = antes == NAO_REGISTRADA || antes == destino ? Operacao.REGISTRAR : Operacao.CORRIGIR;
            // Repetição de fato registrado, inclusive em reserva substituída, é sem escrita.
            if (antes == destino) {
                temporal.validarAgendamento(fato, true);
                return new Resultado(id, fato.getVersion(), new PersistenciaIndicadoresVinculoEnsaioService.Resultado(
                        PersistenciaIndicadoresVinculoEnsaioService.Situacao.SEM_ALTERACAO, false, local.getVersion()));
            }
        }
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

        boolean empateAnterior = correcao && indicadores.empateConhecidoAntesDaCorrecao(local);
        if (regularizacao) fato.registrarFatoEmReservaCancelada(destino);
        else fato.setSituacaoAtendimento(SituacaoAtendimento.valueOf(destino.name()));
        em.flush();
        var resultado = indicadores.aplicar(indicadores.avaliarAposCorrecao(local, empateAnterior), ator);
        historico.registrarIndividual(fato, evento, switch (operacao) {
                    case REGISTRAR -> "Frequência individual registrada no ensaio.";
                    case CORRIGIR -> "Frequência individual corrigida no ensaio.";
                    case REGULARIZAR -> "Frequência regularizada preservando a reserva cancelada.";
                    case PATCH -> throw new IllegalStateException("Comando de status não resolvido.");
                });
        var agora = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var unidadeAuditoria = escopo.unidadePainel().valor();
        if (correcao)
            em.persist(new AuditoriaCorrecaoFrequencia(fato, null, null, fato.getPaciente(), ator, fato.getUsuario(),
                    unidadeAuditoria, antes, destino, motivo, agora, versao, fato.getVersion()));
        if (regularizacao)
            em.persist(new AuditoriaRegularizacaoCancelamento(fato, ator, unidadeAuditoria,
                    "REGISTRO_FATO_ASSISTENCIAL", motivo, agora, versao));
        em.flush();
        historico.renumerar(local);
        return new Resultado(id, fato.getVersion(), resultado);
    }

    /** Cancelamento administrativo nunca elimina frequência já comprovada. */
    @Transactional
    public Resultado cancelarReserva(Long id, int versao) {
        var contexto = atores.autorizarOperacional();
        var bloqueados = coordenacao.bloquear(() -> descobrir(id, contexto), contexto, null);
        var fato = bloqueados.agenda().get(id);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(fato.getPaciente().getIdPublico());
        autorizarReserva(fato, ator, contexto.unidade());
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, fato.getVersion());
        if (fato.getFrequenciaAssistencial() != NAO_REGISTRADA
                || fato.getSituacaoReserva() == SituacaoReserva.SUBSTITUIDA
                || agenda.existsByAgendamentoOriginal_Id(id))
            throw new ConflictException("Cancelamento não pode apagar fato ou cadeia de remarcação.");
        historico.individual(fato, local, NAO_REGISTRADA);
        if (fato.getSituacaoReserva() == SituacaoReserva.CANCELADA)
            return semAlteracao(fato, local);
        var anterior = fato.getSituacaoAtendimento();
        fato.setSituacaoAtendimento(SituacaoAtendimento.CANCELADO);
        em.flush();
        var resultado = indicadores.aplicar(indicadores.avaliar(local), ator);
        historicoReservas.registrarAlteracaoDeAtendimento(fato, anterior);
        return new Resultado(id, fato.getVersion(), resultado);
    }

    @Transactional
    public Resultado recuperarReserva(Long id, int versao, String justificativa) {
        var contexto = atores.autorizar(false);
        var bloqueados = coordenacao.bloquear(() -> descobrir(id, contexto), contexto, null);
        var fato = bloqueados.agenda().get(id);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(fato.getPaciente().getIdPublico());
        autorizar(fato, ator, contexto.unidade());
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, fato.getVersion());
        if (fato.getSituacaoReserva() != SituacaoReserva.CANCELADA || fato.getFrequenciaAssistencial() != NAO_REGISTRADA
                || agenda.existsByAgendamentoOriginal_Id(id) || correcoes.existsByAgendamento_Id(id)
                || regularizacoes.countByAgendamento_IdAndTipoRegularizacao(id, "REGISTRO_FATO_ASSISTENCIAL") != 0)
            throw new ConflictException("Recuperação exige cancelamento sem frequência anterior.");
        if (local.getStatusPaciente() != StatusPaciente.ATIVO || !indicadores.evidencia(local).comprovada())
            throw new ConflictException("Recuperação exige acompanhamento ativo comprovado.");
        temporal.validarRecuperacao(fato);
        String motivo = justificativas.validar(justificativa);
        historico.individual(fato, local, NAO_REGISTRADA);
        guardasAgenda.validarVagaOperacional(fato.getUsuario(), fato.getPaciente(), fato.getDataAgendamento(),
                fato.getTurnoAgendamento(), fato.getHoraAtendimento(), id, local);
        fato.recuperarReservaCancelada();
        em.flush();
        em.persist(new AuditoriaRegularizacaoCancelamento(fato, ator, contexto.unidade(),
                "RECUPERACAO_RESERVA", motivo, clock.instant().truncatedTo(ChronoUnit.MICROS), versao));
        em.flush();
        return semAlteracao(fato, local);
    }

    private static Resultado semAlteracao(Agendamento fato, com.pet.buscaativa.entities.VinculoAssistencial local) {
        return new Resultado(fato.getId(), fato.getVersion(), new PersistenciaIndicadoresVinculoEnsaioService.Resultado(
                PersistenciaIndicadoresVinculoEnsaioService.Situacao.SEM_ALTERACAO, false, local.getVersion()));
    }

    @Transactional
    public Resultado remarcar(Long originalId, int versao, LocalDate data, LocalTime hora, TurnoEnum turno) {
        return remarcar(originalId, versao, data, hora, turno, null, null, false);
    }

    /** POST de remarcação: permite outro profissional da mesma unidade, sob o mesmo plano. */
    @Transactional
    public Resultado remarcarPorCadastro(com.pet.buscaativa.entities.dto.AgendamentoDTO dto) {
        if (dto == null || dto.agendamentoOriginalId() == null || dto.version() == null || dto.version() < 0
                || dto.pacienteId() == null || dto.usuarioId() == null
                || dto.agendamentoOriginalId().equals(dto.id()))
            throw new ValidationException("Informe original, paciente, profissional e versão da remarcação.");
        return remarcar(dto.agendamentoOriginalId(), dto.version(), dto.dataAgendamento(), dto.horaAtendimento(),
                dto.turnoAgendamento(), dto.pacienteId(), dto.usuarioId(), true);
    }

    private Resultado remarcar(Long originalId, int versao, LocalDate data, LocalTime hora, TurnoEnum turno,
            UUID pacienteEsperado, UUID profissionalNovo, boolean cadastro) {
        if (data == null || hora == null || turno == null) throw new ValidationException("Informe o horário da remarcação.");
        var contexto = cadastro ? atores.autorizarOperacional() : atores.autorizar(false);
        if (cadastro && contexto.perfil() != TipoUsuario.ADMINISTRADOR && contexto.perfil() != TipoUsuario.RECEPCAO)
            throw new AccessDeniedException("Perfil sem permissão para remarcação administrativa.");
        var bloqueados = coordenacao.bloquear(() -> descobrir(originalId, contexto, profissionalNovo), contexto, profissionalNovo);
        var original = bloqueados.agenda().get(originalId);
        var ator = bloqueados.atores().ator();
        var local = bloqueados.vinculos().get(original.getPaciente().getIdPublico());
        if (cadastro) {
            autorizarReserva(original, ator, contexto.unidade());
            if (!pacienteEsperado.equals(original.getPaciente().getIdPublico()))
                throw new ValidationException("Remarcação exige o mesmo paciente.");
        } else autorizar(original, ator, contexto.unidade());
        var profissional = cadastro ? bloqueados.atores().referencia() : original.getUsuario();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao, original.getVersion());
        if (data.isBefore(LocalDate.now(clock)))
            throw new ValidationException("Não é permitido criar agendamento em data passada.");
        var antes = original.getSituacaoAtendimento();
        if (!indicadores.evidencia(local).comprovada() || local.getStatusPaciente() != StatusPaciente.ATIVO || local.getTipoAcompanhamento() == null
                || local.getTipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO
                || original.getSituacaoReserva() != SituacaoReserva.AGENDADA
                        && original.getSituacaoReserva() != SituacaoReserva.REMARCADA
                || !List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.FALTOU).contains(antes)
                || agenda.existsByAgendamentoOriginal_Id(originalId)
                || original.getDataAgendamento() == null || original.getHoraAtendimento() == null
                || !LocalDateTime.of(original.getDataAgendamento(), original.getHoraAtendimento()).isBefore(LocalDateTime.of(data, hora)))
            throw new ConflictException("O atendimento não permite criar sucessor por remarcação.");
        guardasAgenda.validarVagaOperacional(profissional, original.getPaciente(), data, turno, hora, null, local);

        var sucessor = new Agendamento();
        sucessor.setPaciente(original.getPaciente());
        sucessor.setUsuario(profissional);
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

    private void autorizarReserva(Agendamento a, com.pet.buscaativa.entities.Usuario ator, String unidade) {
        autorizacao.validarAgendamentoLocal(a);
        if (!AtoresEstadoClinicoLocalEnsaio.elegivel(a.getUsuario(), unidade, false)
                || (ator.getTipoUsuario() == TipoUsuario.PROFISSIONAL && !ator.getId().equals(a.getUsuario().getId())))
            throw new AccessDeniedException("Usuário sem autorização para esta reserva.");
    }

    private Descoberta<List<Fato>> descobrir(Long id, AtoresEstadoClinicoLocalEnsaio.Contexto contexto) {
        return descobrir(id, contexto, null);
    }

    private Descoberta<List<Fato>> descobrir(Long id, AtoresEstadoClinicoLocalEnsaio.Contexto contexto, UUID referencia) {
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
        return CoordenacaoEscritaClinicaEnsaio.descoberta(locais, pacientes, contexto, referencia,
                cadeia.stream().map(Fato::profissional).toList(), List.of(), List.of(),
                cadeia.stream().map(Fato::id).toList(), List.copyOf(cadeia));
    }
}

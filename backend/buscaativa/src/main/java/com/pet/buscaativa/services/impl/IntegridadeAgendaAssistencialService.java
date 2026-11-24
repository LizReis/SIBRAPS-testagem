package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.ConflitosAgendaAssistencialRepository;
import lombok.RequiredArgsConstructor;

/** Classificação interna do mesmo instante. Não resolve fatos nem altera entidades. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class IntegridadeAgendaAssistencialService {
    private final ConflitosAgendaAssistencialRepository conflitos;
    private final AgendamentoRepository atendimentos;
    private final EscopoUnidadeService escopo;
    private final AutorizacaoVinculoAssistencialService autorizacaoLocal;
    private final EntityManager em;

    public enum Sinal {
        OCUPACAO_INDIVIDUAL, PRESENCA_INDIVIDUAL, FALTA_INDIVIDUAL, INDIVIDUAL_PENDENTE,
        INSCRICAO_AGENDADA, PRESENCA_GRUPO, FALTA_GRUPO, FREQUENCIA_GRUPO_DESCONHECIDA,
        SESSAO_AGENDADA, SESSAO_REALIZADA
    }

    public record Instante(LocalDate data, LocalTime hora) {
        public Instante {
            java.util.Objects.requireNonNull(data, "Data assistencial obrigatória.");
            java.util.Objects.requireNonNull(hora, "Hora assistencial obrigatória.");
        }
    }

    /** Exclusões internas, nunca IDs arbitrários do cliente; somente o próprio registro autorizado.
     * Na remarcação e regeneração, validar a identidade/estado da origem antes de excluir.
     */
    public record Exclusao(Long atendimento, Long sessao) {
        public static Exclusao nenhuma() { return new Exclusao(null, null); }
    }

    public record Avaliacao(Set<Sinal> sinais) {
        public Avaliacao { sinais = Set.copyOf(sinais); }
        public boolean tem(Sinal sinal) { return sinais.contains(sinal); }
        public boolean temImpedimentoOperacional() {
            return tem(Sinal.OCUPACAO_INDIVIDUAL) || tem(Sinal.INSCRICAO_AGENDADA)
                    || tem(Sinal.SESSAO_AGENDADA) || temSinalFactualIncompativel();
        }
        public boolean temSinalFactualIncompativel() {
            return tem(Sinal.PRESENCA_INDIVIDUAL) || tem(Sinal.PRESENCA_GRUPO) || tem(Sinal.SESSAO_REALIZADA);
        }
        public boolean temInformacaoInconclusiva() {
            return tem(Sinal.INDIVIDUAL_PENDENTE) || tem(Sinal.FREQUENCIA_GRUPO_DESCONHECIDA);
        }
    }

    public Avaliacao avaliarPaciente(Paciente p, Instante quando, Exclusao excluir) {
        exigirLock(p);
        escopo.validarAcesso(p);
        return sinaisPaciente(p, quando, excluir);
    }

    Avaliacao avaliarPaciente(VinculoAssistencial local, Instante quando, Exclusao excluir) {
        var paciente = local.getPaciente();
        exigirLock(paciente);
        exigirLock(local);
        autorizacaoLocal.exigirVinculoIdentificado(paciente.getIdPublico(), local.getIdPublico());
        return sinaisPaciente(paciente, quando, excluir);
    }

    private Avaliacao sinaisPaciente(Paciente p, Instante quando, Exclusao excluir) {
        var d = quando.data(); var h = quando.hora(); var a = excluir.atendimento(); var s = excluir.sessao();
        var resultado = EnumSet.noneOf(Sinal.class);
        incluir(resultado, Sinal.OCUPACAO_INDIVIDUAL, conflitos.ocupacaoPaciente(p,d,h,a));
        incluir(resultado, Sinal.PRESENCA_INDIVIDUAL, atendimentos.existsPresencaDoPaciente(p,d,h,a));
        incluir(resultado, Sinal.FALTA_INDIVIDUAL, conflitos.frequenciaIndividualPaciente(p,d,h,a,FrequenciaAssistencial.FALTOU));
        incluir(resultado, Sinal.INDIVIDUAL_PENDENTE, conflitos.individualPendentePaciente(p,d,h,a));
        incluir(resultado, Sinal.INSCRICAO_AGENDADA, conflitos.inscricaoAgendada(p,d,h,s));
        incluir(resultado, Sinal.PRESENCA_GRUPO, conflitos.frequenciaGrupoRealizado(p,d,h,s,StatusPresencaGrupo.PRESENTE));
        incluir(resultado, Sinal.FALTA_GRUPO, conflitos.frequenciaGrupoRealizado(p,d,h,s,StatusPresencaGrupo.FALTOU));
        incluir(resultado, Sinal.FREQUENCIA_GRUPO_DESCONHECIDA, conflitos.frequenciaGrupoRealizado(p,d,h,s,StatusPresencaGrupo.NAO_REGISTRADA));
        return new Avaliacao(resultado);
    }

    public Avaliacao avaliarProfissional(Usuario u, Instante quando, Exclusao excluir) {
        exigirLock(u);
        escopo.validarUsuario(u);
        var d = quando.data(); var h = quando.hora(); var a = excluir.atendimento(); var s = excluir.sessao();
        var resultado = EnumSet.noneOf(Sinal.class);
        incluir(resultado, Sinal.OCUPACAO_INDIVIDUAL, conflitos.ocupacaoProfissional(u,d,h,a));
        incluir(resultado, Sinal.PRESENCA_INDIVIDUAL, atendimentos.existsPresencaDoProfissional(u,d,h,a));
        incluir(resultado, Sinal.INDIVIDUAL_PENDENTE, conflitos.individualPendenteProfissional(u,d,h,a));
        incluir(resultado, Sinal.SESSAO_AGENDADA, conflitos.sessaoAgendada(u,d,h,s));
        incluir(resultado, Sinal.SESSAO_REALIZADA, conflitos.sessaoRealizada(u,d,h,s));
        return new Avaliacao(resultado);
    }

    private void exigirLock(Object sujeito) {
        if (!em.contains(sujeito)) throw new IllegalStateException("Recurso da agenda deve estar gerenciado e bloqueado.");
        var modo = em.getLockMode(sujeito);
        if (modo != LockModeType.PESSIMISTIC_WRITE && modo != LockModeType.PESSIMISTIC_FORCE_INCREMENT)
            throw new IllegalStateException("Adquira o lock de escrita do recurso antes de avaliar conflitos.");
    }

    private static void incluir(EnumSet<Sinal> destino, Sinal sinal, boolean encontrado) {
        if (encontrado) destino.add(sinal);
    }
}

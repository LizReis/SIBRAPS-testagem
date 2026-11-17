package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.repositories.DisponibilidadeExcecaoRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class IntegridadeOfertaService {
    private static final List<SituacaoAtendimento> OCUPANTES = List.of(
            SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO, SituacaoAtendimento.PRESENTE);
    private final AgendamentoRepository agendamentos;
    private final DisponibilidadeRepository semanal;
    private final DisponibilidadeExcecaoRepository excecoes;
    private final EntityManager em;
    private final UsuarioContextService contexto;
    private final Clock clock;

    public record Origem(UUID dono, List<Object> assinatura) {}

    // Consulta apenas campos escalares; nenhuma entidade mutável é materializada antes dos locks.
    public Origem origem(Class<?> tipo, Long id) {
        if (id == null) return null;
        String campos;
        if (tipo == com.pet.buscaativa.entities.Disponibilidade.class)
            campos = "c.diaDaSemana, c.turno, c.capacidade";
        else if (tipo == com.pet.buscaativa.entities.DisponibilidadeExcecao.class)
            campos = "c.data, c.turno, c.capacidade";
        else if (tipo == com.pet.buscaativa.entities.BloqueioAgenda.class)
            campos = "c.dataInicio, c.dataFim, c.motivoBloqueio";
        else throw new IllegalArgumentException("Tipo de configuração desconhecido.");
        var rows = em.createQuery("select c.usuario.idPublico, " + campos + " from " + tipo.getSimpleName()
                + " c where c.id = :id", Object[].class).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new ResourceNotFoundException("Configuração não encontrada.");
        var row = rows.getFirst();
        return new Origem((UUID) row[0], java.util.Arrays.asList(row[1], row[2], row[3]));
    }

    public Usuario bloquear(UUID destino, Origem origem, String email) {
        return contexto.bloquearConfiguracao(destino, origem == null ? null : origem.dono(), email);
    }

    public <T> T carregar(Class<T> tipo, Long id) {
        T item = em.find(tipo, id, LockModeType.PESSIMISTIC_WRITE);
        if (item == null) throw new ResourceNotFoundException("Configuração não encontrada.");
        em.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        return item;
    }

    public void conferirOrigem(Object item, Origem origem, String email) {
        Usuario atual;
        List<Object> assinatura;
        if (item instanceof com.pet.buscaativa.entities.Disponibilidade d) {
            atual=d.getUsuario(); assinatura=java.util.Arrays.asList(d.getDiaDaSemana(),d.getTurno(),d.getCapacidade());
        } else if (item instanceof com.pet.buscaativa.entities.DisponibilidadeExcecao d) {
            atual=d.getUsuario(); assinatura=java.util.Arrays.asList(d.getData(),d.getTurno(),d.getCapacidade());
        } else if (item instanceof com.pet.buscaativa.entities.BloqueioAgenda d) {
            atual=d.getUsuario(); assinatura=java.util.Arrays.asList(d.getDataInicio(),d.getDataFim(),d.getMotivoBloqueio());
        } else throw new IllegalArgumentException("Tipo de configuração desconhecido.");
        if (atual == null || !Objects.equals(atual.getIdPublico(), origem.dono())
                || !assinatura.equals(origem.assinatura()))
            throw new ConflictException("A configuração mudou durante a operação; atualize os dados.");
        contexto.validarAlteracao(atual, email);
    }

    public void validarSemanal(Usuario u, DayOfWeek dia, TurnoEnum turno, int capacidade) {
        // Uma exceção existente mantém a sua própria capacidade; a semanal não a altera.
        for (LocalDate data : agendamentos.datasOcupadasParaOferta(u, LocalDate.now(clock), turno, OCUPANTES)) {
            if (data.getDayOfWeek() != dia || excecoes.findByUsuarioAndDataAndTurno(u, data, turno).isPresent())
                continue;
            validarCapacidade(u, data, turno, capacidade);
        }
    }

    public void validarExcecao(Usuario u, LocalDate data, TurnoEnum turno, int capacidade) {
        validarCapacidade(u, data, turno, capacidade);
    }

    public void validarRetiradaExcecao(Usuario u, LocalDate data, TurnoEnum turno) {
        int capacidade = semanal.findByUsuarioAndDiaDaSemanaAndTurno(u, data.getDayOfWeek(), turno)
                .map(d -> d.getCapacidade()).orElse(0);
        validarCapacidade(u, data, turno, capacidade);
    }

    private void validarCapacidade(Usuario u, LocalDate data, TurnoEnum turno, int capacidade) {
        int ocupadas = agendamentos.contarVagasOcupadasBySituacoes(u, data, turno, OCUPANTES);
        if (capacidade < ocupadas)
            throw new ConflictException("A alteração de oferta deixaria atendimentos existentes sem capacidade suficiente.");
    }

    public void validarBloqueio(Usuario u, LocalDate inicio, LocalDate fim) {
        if (agendamentos.contarAtivosNoPeriodo(u, inicio, fim, OCUPANTES) > 0)
            throw new ConflictException("Não é possível bloquear este período porque existem atendimentos agendados. Cancele ou remarque antes de continuar.");
    }

    public static void validarCamposSemanais(DayOfWeek dia, TurnoEnum turno, Integer capacidade) {
        if (dia == null || turno == null || capacidade == null || capacidade <= 0)
            throw new ValidationException("Informe dia, turno e capacidade maior que zero.");
    }
    public static void validarCamposExcecao(LocalDate dia, TurnoEnum turno, Integer capacidade) {
        if (dia == null || turno == null || capacidade == null || capacidade < 0)
            throw new ValidationException("Informe data, turno e capacidade não negativa.");
    }
}

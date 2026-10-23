package com.pet.buscaativa.repositories;

import java.time.LocalDate;
import java.util.List;
import java.time.LocalTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TurnoEnum;

@Repository
public interface AgendamentoRepository extends JpaRepository<Agendamento, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Agendamento> {
    Optional<Agendamento> findFirstByPacienteAndDataAgendamentoGreaterThanEqualAndSituacaoAtendimentoInOrderByDataAgendamentoAscHoraAtendimentoAsc(
            Paciente paciente, LocalDate data, List<SituacaoAtendimento> situacoes);

    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario WHERE a.paciente = :paciente ORDER BY a.dataAgendamento DESC, a.horaAtendimento DESC")
    List<Agendamento> findDetalhesByPaciente(@Param("paciente") Paciente paciente);

    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario JOIN FETCH a.paciente WHERE a.id = :id")
    java.util.Optional<Agendamento> findByIdWithUsuarioAndPaciente(@Param("id") Long id);

    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario JOIN FETCH a.paciente WHERE a.dataAgendamento = :dataAgendamento ORDER BY a.dataAgendamento, a.turnoAgendamento, a.horaAtendimento, a.id")
    List<Agendamento> findByDataAgendamento(@Param("dataAgendamento") LocalDate dataAgendamento);

    long countByDataAgendamentoAndSituacaoAtendimento(LocalDate dataAgendamento, SituacaoAtendimento situacaoAtendimento);

    long countBySituacaoAtendimentoAndDataAgendamentoBetween(SituacaoAtendimento situacaoAtendimento, LocalDate inicio, LocalDate fim);

    List<Agendamento> findByPacienteOrderByDataAgendamentoDescIdDesc(Paciente paciente);

    List<Agendamento> findByPaciente(Paciente paciente);

    // Conta agendamentos que ocupam vaga com filtro por status
    @Query("SELECT COUNT(a) FROM Agendamento a WHERE a.usuario = :usuario AND a.dataAgendamento = :data AND a.turnoAgendamento = :turno AND a.situacaoAtendimento IN :situacoes")
    int contarVagasOcupadasBySituacoes(@Param("usuario") Usuario usuario,
                                       @Param("data") LocalDate dataAgendamento,
                                       @Param("turno") TurnoEnum turnoAgendamento,
                                       @Param("situacoes") List<SituacaoAtendimento> situacoes);

    @Query("SELECT COUNT(a) FROM Agendamento a WHERE a.usuario = :usuario AND a.dataAgendamento = :data AND a.turnoAgendamento = :turno AND a.situacaoAtendimento <> :situacao")
    int contarVagasOcupadas(@Param("usuario") Usuario usuario,
                            @Param("data") LocalDate dataAgendamento,
                            @Param("turno") TurnoEnum turnoAgendamento,
                            @Param("situacao") SituacaoAtendimento situacao);

    @Query("SELECT COUNT(a) FROM Agendamento a WHERE a.usuario = :usuario AND a.dataAgendamento BETWEEN :inicio AND :fim AND a.situacaoAtendimento IN :situacoes")
    long contarAtivosNoPeriodo(@Param("usuario") Usuario usuario,
                               @Param("inicio") LocalDate inicio,
                               @Param("fim") LocalDate fim,
                               @Param("situacoes") List<SituacaoAtendimento> situacoes);


    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario JOIN FETCH a.paciente WHERE a.dataAgendamento = :dataAgendamento AND a.usuario = :usuario ORDER BY a.dataAgendamento, a.turnoAgendamento, a.horaAtendimento, a.id")
    List<Agendamento> findByDataAgendamentoAndUsuario(@Param("dataAgendamento") LocalDate dataAgendamento, @Param("usuario") Usuario usuario);

    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario JOIN FETCH a.paciente WHERE a.dataAgendamento BETWEEN :dataInicio AND :dataFim ORDER BY a.dataAgendamento, a.turnoAgendamento, a.horaAtendimento, a.id")
    List<Agendamento> findAgendaByDataAgendamentoBetween(@Param("dataInicio") LocalDate dataInicio, @Param("dataFim") LocalDate dataFim);

    @Query("SELECT a FROM Agendamento a JOIN FETCH a.usuario JOIN FETCH a.paciente WHERE a.usuario = :usuario AND a.dataAgendamento BETWEEN :dataInicio AND :dataFim ORDER BY a.dataAgendamento, a.turnoAgendamento, a.horaAtendimento, a.id")
    List<Agendamento> findAgendaByUsuarioAndDataAgendamentoBetween(@Param("usuario") Usuario usuario, @Param("dataInicio") LocalDate dataInicio, @Param("dataFim") LocalDate dataFim);

    //Busca agendamentos do usuário em um intervalo
    List<Agendamento> findByUsuarioAndDataAgendamentoBetween(Usuario usuario, LocalDate startInclusive, LocalDate endInclusive);

    @Query("SELECT COUNT(a) FROM Agendamento a WHERE a.usuario = :usuario " +
            "AND a.dataAgendamento >= :dataInicio AND a.situacaoAtendimento IN :situacoes " +
            "AND FUNCTION('DAY_OF_WEEK', a.dataAgendamento) = :diaBanco AND a.turnoAgendamento = :turno")
    long contarConflitosDisponibilidade(@Param("usuario") Usuario usuario,
                                        @Param("dataInicio") LocalDate dataInicio, @Param("diaBanco") int diaBanco,
                                        @Param("turno") TurnoEnum turno, @Param("situacoes") List<SituacaoAtendimento> situacoes);

    @Query("SELECT COUNT(a) FROM Agendamento a WHERE a.usuario = :usuario " +
            "AND a.dataAgendamento >= :dataInicio AND a.situacaoAtendimento IN :situacoes " +
            "AND FUNCTION('DAY_OF_WEEK', a.dataAgendamento) = :diaBanco AND a.turnoAgendamento = :turno " +
            "GROUP BY a.dataAgendamento")
    List<Long> contarOcupacaoPorDataDisponibilidade(@Param("usuario") Usuario usuario,
                                                    @Param("dataInicio") LocalDate dataInicio, @Param("diaBanco") int diaBanco,
                                                    @Param("turno") TurnoEnum turno, @Param("situacoes") List<SituacaoAtendimento> situacoes);


    // Verifica se já existe um agendamento ativo do profissional na mesma data e horário exato
    @Query("SELECT COUNT(a) > 0 FROM Agendamento a WHERE a.usuario = :usuario " +
            "AND a.dataAgendamento = :data AND a.horaAtendimento = :hora " +
            "AND a.situacaoAtendimento IN :situacoes")
    boolean existsAgendamentoAtivoNoMesmoHorario(@Param("usuario") Usuario usuario,
                                                 @Param("data") LocalDate data, @Param("hora") LocalTime hora,
                                                 @Param("situacoes") List<SituacaoAtendimento> situacoes);

    @Query("SELECT COUNT(a) > 0 FROM Agendamento a WHERE a.paciente = :paciente AND a.dataAgendamento = :data AND a.horaAtendimento = :hora AND a.situacaoAtendimento IN :situacoes")
    boolean existsAtivoDoPacienteNoMesmoHorario(@Param("paciente") Paciente paciente, @Param("data") LocalDate data,
            @Param("hora") LocalTime hora, @Param("situacoes") List<SituacaoAtendimento> situacoes);
    @Query("SELECT a.horaAtendimento FROM Agendamento a WHERE a.usuario = :usuario AND a.dataAgendamento = :data AND a.turnoAgendamento = :turno AND a.situacaoAtendimento IN :situacoes ORDER BY a.horaAtendimento")
    List<LocalTime> findHorariosOcupados(@Param("usuario") Usuario usuario, @Param("data") LocalDate data,
            @Param("turno") TurnoEnum turno, @Param("situacoes") List<SituacaoAtendimento> situacoes);
}

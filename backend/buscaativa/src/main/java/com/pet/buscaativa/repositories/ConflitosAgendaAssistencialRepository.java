package com.pet.buscaativa.repositories;

import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;

/** Consultas pontuais de integridade, sem retornar identidade de terceiros. */
public interface ConflitosAgendaAssistencialRepository extends Repository<Agendamento, Long> {
    @Query("select count(a)>0 from Agendamento a where a.paciente=:paciente and a.dataAgendamento=:data and a.horaAtendimento=:hora and a.ocupaVaga=true and (:excluir is null or a.id<>:excluir)")
    boolean ocupacaoPaciente(@Param("paciente") Paciente paciente, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(a)>0 from Agendamento a where a.usuario=:usuario and a.dataAgendamento=:data and a.horaAtendimento=:hora and a.ocupaVaga=true and (:excluir is null or a.id<>:excluir)")
    boolean ocupacaoProfissional(@Param("usuario") Usuario usuario, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(a)>0 from Agendamento a where a.paciente=:paciente and a.dataAgendamento=:data and a.horaAtendimento=:hora and a.migracaoPendente=true and (:excluir is null or a.id<>:excluir)")
    boolean individualPendentePaciente(@Param("paciente") Paciente paciente, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(a)>0 from Agendamento a where a.usuario=:usuario and a.dataAgendamento=:data and a.horaAtendimento=:hora and a.migracaoPendente=true and (:excluir is null or a.id<>:excluir)")
    boolean individualPendenteProfissional(@Param("usuario") Usuario usuario, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(a)>0 from Agendamento a where a.paciente=:paciente and a.dataAgendamento=:data and a.horaAtendimento=:hora and a.frequenciaAssistencial=:frequencia and (:excluir is null or a.id<>:excluir)")
    boolean frequenciaIndividualPaciente(@Param("paciente") Paciente paciente, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir, @Param("frequencia") FrequenciaAssistencial frequencia);

    @Query("select count(p)>0 from SessaoGrupoParticipante p where p.paciente=:paciente and p.sessaoGrupo.dataSessao=:data and p.sessaoGrupo.horario=:hora and p.sessaoGrupo.status=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.AGENDADA and (:excluir is null or p.sessaoGrupo.id<>:excluir)")
    boolean inscricaoAgendada(@Param("paciente") Paciente paciente, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(p)>0 from SessaoGrupoParticipante p where p.paciente=:paciente and p.sessaoGrupo.dataSessao=:data and p.sessaoGrupo.horario=:hora and p.sessaoGrupo.status=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.REALIZADA and p.statusPresenca=:frequencia and (:excluir is null or p.sessaoGrupo.id<>:excluir)")
    boolean frequenciaGrupoRealizado(@Param("paciente") Paciente paciente, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir, @Param("frequencia") StatusPresencaGrupo frequencia);

    @Query("select count(s)>0 from SessaoGrupo s where s.grupo.coordenador=:usuario and s.dataSessao=:data and s.horario=:hora and s.status=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.AGENDADA and (:excluir is null or s.id<>:excluir)")
    boolean sessaoAgendada(@Param("usuario") Usuario usuario, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);

    @Query("select count(s)>0 from SessaoGrupo s where s.responsavelAtendimento=:usuario and s.dataSessao=:data and s.horario=:hora and s.status=com.pet.buscaativa.entities.enums.StatusSessaoGrupo.REALIZADA and s.responsabilidadePendente=false and (:excluir is null or s.id<>:excluir)")
    boolean sessaoRealizada(@Param("usuario") Usuario usuario, @Param("data") LocalDate data, @Param("hora") LocalTime hora, @Param("excluir") Long excluir);
}

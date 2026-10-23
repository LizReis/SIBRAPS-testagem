package com.pet.buscaativa.repositories;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;

@Repository
public interface DisponibilidadeRepository extends JpaRepository<Disponibilidade, Long>{
    
    //Busca a disponibilidade do profissional pelo dia da semana e o turno
    Optional<Disponibilidade> findByUsuarioAndDiaDaSemanaAndTurno(Usuario usuario, DayOfWeek diaDaSemana, TurnoEnum turno);

    //Busca a disponibilidade do profissional pelo dia da semana 
    List<Disponibilidade> findByUsuarioAndDiaDaSemana(Usuario usuario, DayOfWeek diaDaSemana);

    List<Disponibilidade> findByUsuario(Usuario usuario);
}

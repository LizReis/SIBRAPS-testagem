package com.pet.buscaativa.repositories;

import com.pet.buscaativa.entities.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.pet.buscaativa.entities.enums.TipoUsuario;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;


@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Usuario> {

    Optional<Usuario> findByEmail(String email);

    Optional<Usuario> findByIdPublico(UUID idPublico);

    List<Usuario> findAllByTipoUsuarioOrderByNome(TipoUsuario tipoUsuario);
    List<Usuario> findAllByTipoUsuarioNotOrderByNome(TipoUsuario tipoUsuario);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.idPublico = :idPublico")
    Optional<Usuario> findByIdPublicoForUpdate(@Param("idPublico") UUID idPublico);
}

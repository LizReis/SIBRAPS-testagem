package com.pet.buscaativa.validation;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.Disponibilidade;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;
import com.pet.buscaativa.repositories.DisponibilidadeRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

/**
 * Validador que verifica se já existe Disponibilidade para o usuarioId + diaSemana + turno.
 * Observações:
 * - Se usuarioId for nulo, o validator retorna true (a validação definitiva será feita no Service).
 * - Se existir disponibilidade distinta contendo usuario, dia da semana e turno iguais  retorna false.
 */
@Component
@RequiredArgsConstructor
public class UniqueDisponibilidadeValidator implements ConstraintValidator<UniqueDisponibilidade, DisponibilidadeDTO> {

    private final DisponibilidadeRepository disponibilidadeRepository;
    private final UsuarioRepository usuarioRepository;

    @Override
    public boolean isValid(DisponibilidadeDTO dto, ConstraintValidatorContext context) {
        if (dto == null) return true;

        UUID usuarioId = dto.usuarioId();
        if (usuarioId == null) {
            // Não temos info de usuário no DTO — validação será feita no service onde se conhece o usuário logado.
            return true;
        }

        Optional<Usuario> usuarioOpt = usuarioRepository.findByIdPublico(usuarioId);
        if (usuarioOpt.isEmpty()) {
            // Se usuário não existe, validador deixa passar; outra validação (service/controller) tratará usuário inválido.
            return true;
        }

        Usuario usuario = usuarioOpt.get();
        Optional<Disponibilidade> existente = disponibilidadeRepository.findByUsuarioAndDiaDaSemanaAndTurno(usuario, dto.diaSemana(), dto.turno());
        if (existente.isPresent()) {
            // se for o mesmo registro (atualização), é válido
            Disponibilidade d = existente.get();
            if (dto.id() != null && d.getId().equals(dto.id())) {
                return true;
            }
            // existe outro registro igual -> inválido
            return false;
        }

        return true;
    }
}
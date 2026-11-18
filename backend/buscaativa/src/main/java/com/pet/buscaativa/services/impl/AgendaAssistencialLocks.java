package com.pet.buscaativa.services.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import lombok.RequiredArgsConstructor;

/** Primeira fase do protocolo: obter todos os sujeitos antes de bloquear grupo/sessão/atendimento ou escrever. */
@Service
@RequiredArgsConstructor
public class AgendaAssistencialLocks {
    private final PacienteRepository pacientes;
    private final UsuarioRepository usuarios;
    private final EscopoUnidadeService escopo;
    private final EntityManager em;

    public record Recursos(Map<UUID, Paciente> pacientes, Map<UUID, Usuario> profissionais) {
        public Recursos {
            pacientes = Collections.unmodifiableMap(new LinkedHashMap<>(pacientes));
            profissionais = Collections.unmodifiableMap(new LinkedHashMap<>(profissionais));
        }
        public Paciente paciente(UUID id) {
            var p = pacientes.get(id);
            if (p == null) throw new IllegalStateException("Paciente não incluído no plano de locks.");
            return p;
        }
        public Usuario profissional(UUID id) {
            var u = profissionais.get(id);
            if (u == null) throw new IllegalStateException("Profissional não incluído no plano de locks.");
            return u;
        }
    }

    /** A autorização clínica e a elegibilidade operacional específicas continuam no serviço chamador.
     * Não chamar depois de adquirir locks de grupo/registro ou de alterar entidades gerenciadas.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Recursos bloquear(Collection<UUID> idsPacientes, Collection<UUID> idsProfissionais) {
        var ordemPacientes = ordenar(idsPacientes);
        var ordemProfissionais = ordenar(idsProfissionais);
        var ps = new LinkedHashMap<UUID, Paciente>();
        var us = new LinkedHashMap<UUID, Usuario>();
        for (UUID id : ordemPacientes) {
            var p = pacientes.findByIdPublicoForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado."));
            em.refresh(p, LockModeType.PESSIMISTIC_WRITE);
            escopo.validarAcesso(p);
            ps.put(id, p);
        }
        for (UUID id : ordemProfissionais) {
            var u = usuarios.findByIdPublicoForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Profissional não encontrado."));
            em.refresh(u, LockModeType.PESSIMISTIC_WRITE);
            escopo.validarUsuario(u);
            us.put(id, u);
        }
        return new Recursos(ps, us);
    }

    private TreeSet<UUID> ordenar(Collection<UUID> ids) {
        if (ids == null || ids.stream().anyMatch(java.util.Objects::isNull))
            throw new ValidationException("Identidades dos recursos da agenda inválidas.");
        return new TreeSet<>(ids);
    }
}

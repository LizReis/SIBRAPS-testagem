package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.AgendaAssistencialLocks;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;

/** Contrato de ordem da camada de locks; a proteção física é verificada na suíte de integração. */
class AgendaAssistencialLocks62Tests {
    private final PacienteRepository patients=mock(PacienteRepository.class);
    private final UsuarioRepository users=mock(UsuarioRepository.class);
    private final EscopoUnidadeService scope=mock(EscopoUnidadeService.class);
    private final EntityManager em=mock(EntityManager.class);
    private final AgendaAssistencialLocks locks=new AgendaAssistencialLocks(patients,users,scope,em);

    @Test void ar15AcquireAllPatientsBeforeAnyProfessionalAndRefreshBeforeScopeCheck() {
        UUID a=new UUID(0,1), b=new UUID(0,2), c=new UUID(0,3), d=new UUID(0,4);
        Paciente pa=new Paciente(), pb=new Paciente(); Usuario uc=new Usuario(), ud=new Usuario();
        when(patients.findByIdPublicoForUpdate(a)).thenReturn(Optional.of(pa));
        when(patients.findByIdPublicoForUpdate(b)).thenReturn(Optional.of(pb));
        when(users.findByIdPublicoForUpdate(c)).thenReturn(Optional.of(uc));
        when(users.findByIdPublicoForUpdate(d)).thenReturn(Optional.of(ud));
        var r=locks.bloquear(List.of(b,a,a),List.of(d,c,d));
        var seq=inOrder(patients,users,em,scope);
        seq.verify(patients).findByIdPublicoForUpdate(a);seq.verify(em).refresh(same(pa),eq(LockModeType.PESSIMISTIC_WRITE));seq.verify(scope).validarAcesso(same(pa));
        seq.verify(patients).findByIdPublicoForUpdate(b);seq.verify(em).refresh(same(pb),eq(LockModeType.PESSIMISTIC_WRITE));seq.verify(scope).validarAcesso(same(pb));
        seq.verify(users).findByIdPublicoForUpdate(c);seq.verify(em).refresh(same(uc),eq(LockModeType.PESSIMISTIC_WRITE));seq.verify(scope).validarUsuario(same(uc));
        seq.verify(users).findByIdPublicoForUpdate(d);seq.verify(em).refresh(same(ud),eq(LockModeType.PESSIMISTIC_WRITE));seq.verify(scope).validarUsuario(same(ud));
        verifyNoMoreInteractions(patients,users,em,scope);
        assertSame(pa,r.paciente(a));assertSame(ud,r.profissional(d));
        assertThrows(IllegalStateException.class,()->r.paciente(c));
    }
    @Test void ar16InvalidPlanFailsBeforeAnyAcquisition() {
        assertThrows(ValidationException.class,()->locks.bloquear(List.of(new UUID(0,1)),java.util.Arrays.asList(new UUID(0,2),null)));
        verifyNoInteractions(patients,users,scope,em);
    }
}

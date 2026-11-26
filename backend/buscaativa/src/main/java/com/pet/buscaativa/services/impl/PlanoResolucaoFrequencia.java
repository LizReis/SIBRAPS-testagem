package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import org.springframework.security.access.AccessDeniedException;

/** Validação comum ao futuro comando, sem inferir duplicidade a partir de horário. */
final class PlanoResolucaoFrequencia {
    private PlanoResolucaoFrequencia() {}
    record Referencia(LinhaDoTempoFrequenciasVinculoService.Fonte fonte, Long id, Integer versao) {}
    record Fato(Referencia referencia, Long responsavel, LocalDateTime horario, boolean presente) {}

    static void validar(Referencia valido, Referencia desconsiderado, Fato a, Fato b,
            Long ator, TipoUsuario perfil) {
        if (valido==null || desconsiderado==null || a==null || b==null
                || valido.fonte()==null || desconsiderado.fonte()==null
                || valido.id()==null || desconsiderado.id()==null
                || valido.id()<=0 || desconsiderado.id()<=0
                || valido.versao()==null || desconsiderado.versao()==null
                || valido.versao()<0 || desconsiderado.versao()<0
                || valido.fonte()==desconsiderado.fonte() && valido.id().equals(desconsiderado.id()))
            throw new ValidationException("Selecione dois fatos distintos e suas versões.");
        if (!valido.equals(a.referencia()) || !desconsiderado.equals(b.referencia()))
            throw new ConflictException("Os fatos foram alterados. Consulte novamente.");
        if (a.horario()==null || !a.horario().equals(b.horario()))
            throw new ConflictException("Este fluxo inicial exige fatos no mesmo instante. Divergências temporais exigem revisão própria.");
        if (a.responsavel()==null || b.responsavel()==null)
            throw new ConflictException("Responsabilidade histórica inconclusiva.");
        if (ator==null || perfil!=TipoUsuario.ADMINISTRADOR && (perfil!=TipoUsuario.PROFISSIONAL
                || !Objects.equals(ator,a.responsavel()) || !Objects.equals(ator,b.responsavel())))
            throw new AccessDeniedException("Perfil sem autorização para resolver ambos os fatos.");
    }
}

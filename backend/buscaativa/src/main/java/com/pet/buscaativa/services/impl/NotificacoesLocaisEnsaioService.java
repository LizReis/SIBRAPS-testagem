package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.alertas.PacienteVermelhoDTO;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.UsuarioRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Planejamento RF26 somente técnico. Sem SMTP, sem scheduler, sem endpoint.
 * Chave estável é identidade lógica da entrega, não garantia de deduplicação persistente. */
@Service
@Profile("test & ensaio-estado-local")
@RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
public class NotificacoesLocaisEnsaioService {
    private final UsuarioRepository usuarios;
    private final CalculoIndicadoresVinculo calculo;
    private final EvidenciaStatusVinculo evidencia;
    private final EntityManager em;
    private final Clock clock;
    @Value("${app.alertas.zone:America/Bahia}")
    private String zonaRf26;
    public record Chave(LocalDate data,UUID destinatario,String unidade) {}
    public record Entrega(Chave chave,String nome,String email,List<PacienteVermelhoDTO> pacientes) {}

    public List<Entrega> planejar() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)
            throw new AccessDeniedException("Planejamento técnico exige contexto sem autenticação.");
        LocalDate hoje=diaTecnico();
        var porUnidade=new HashMap<String,List<PacienteVermelhoDTO>>();
        var entregas=new ArrayList<Entrega>();
        for(var u:usuarios.findAll()) {
            if(u.getTipoUsuario()!=TipoUsuario.ADMINISTRADOR && u.getTipoUsuario()!=TipoUsuario.PROFISSIONAL) continue;
            if(u.getEmail()==null||u.getEmail().isBlank()) continue;
            String unidade;
            try { unidade=ChaveUnidadeAssistencial.doUsuarioAtual(u); }
            catch(IllegalArgumentException|NullPointerException invalido) { continue; }
            var pacientes=porUnidade.computeIfAbsent(unidade,k->vermelhos(k,hoje));
            entregas.add(new Entrega(new Chave(hoje,u.getIdPublico(),unidade),u.getNome(),u.getEmail(),pacientes));
        }
        return entregas.stream().sorted(Comparator.comparing((Entrega e)->e.chave().unidade())
                .thenComparing(e->e.chave().destinatario())).toList();
    }

    /** Reconstroi o payload a partir da lotação e dos fatos atuais.
     * Nunca reutiliza e-mail, nome ou pacientes de um plano anterior. */
    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE,
            propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<Entrega> revalidar(Chave chave) {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)
            throw new AccessDeniedException("Revalidação técnica exige contexto sem autenticação.");
        if(chave==null || !diaTecnico().equals(chave.data())
                || chave.destinatario()==null || chave.unidade()==null) return Optional.empty();
        return usuarios.findByIdPublico(chave.destinatario())
                .filter(u->AtoresEstadoClinicoLocalEnsaio.elegivel(u,chave.unidade(),false))
                .filter(u->u.getEmail()!=null&&!u.getEmail().isBlank())
                .map(u->new Entrega(chave,u.getNome(),u.getEmail(),vermelhos(chave.unidade(),chave.data())));
    }

    private LocalDate diaTecnico() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(zonaRf26));
    }

    private List<PacienteVermelhoDTO> vermelhos(String unidade,LocalDate hoje) {
        var lista=new ArrayList<PacienteVermelhoDTO>();
        var locais=em.createQuery("select v from VinculoAssistencial v join fetch v.paciente where v.unidadeChave=:unidade and v.statusPaciente=:ativo order by v.id",
                VinculoAssistencial.class).setParameter("unidade",unidade).setParameter("ativo",StatusPaciente.ATIVO).getResultList();
        for(var v:locais) {
            if(!ProjecaoClinicaVinculoService.chaveCanonica(v)||!Boolean.FALSE.equals(v.getIndicadoresPendentes()))continue;
            var c=calculo.calcular(v,hoje,evidencia.analisar(v),v.getIndicadoresPendentes());
            if(c.status().valor().orElse(null)!=StatusPaciente.ATIVO||c.risco().valor().orElse(null)!=ClassificacaoRisco.VERMELHO
                    ||c.faltas().valor().isEmpty()||!c.ultimaPresenca().conclusiva()
                    || !c.visita().conclusiva() || c.visita().valor().isEmpty())continue;
            lista.add(new PacienteVermelhoDTO(v.getPaciente().getNome(),c.faltas().valor().orElseThrow(),c.ultimaPresenca().valor().orElse(null)));
        }
        return List.copyOf(lista);
    }
}

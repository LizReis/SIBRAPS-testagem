package com.pet.buscaativa.services.impl;

import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import static com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;

/** Ponte exclusiva do classpath de teste, sem endpoint ou configuração de produção. */
public final class OperacoesResolucaoFrequenciaTeste {
    private OperacoesResolucaoFrequenciaTeste() {}
    private static TransactionTemplate tx(ApplicationContext app) {
        return new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    }
    private static <T> T como(String email,Supplier<T> acao) {
        var anterior=SecurityContextHolder.getContext();
        var contexto=SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email,null,List.of()));
        SecurityContextHolder.setContext(contexto);
        try {return acao.get();} finally {SecurityContextHolder.setContext(anterior);}
    }
    public record Reserva(Long id,Integer versao) {}
    public static Reserva falta(ApplicationContext app,String email,Long paciente,Long profissional,LocalDate data,int hora) {
        // Apenas fixture: reproduz compromissos antigos coincidentes sem
        // relaxar a prevenção normal de novas reservas incompatíveis.
        var r=tx(app).execute(t->{
            var em=EntityManagerFactoryUtils.getTransactionalEntityManager(app.getBean(jakarta.persistence.EntityManagerFactory.class));
            var a=new Agendamento();
            a.setPaciente(em.find(Paciente.class,paciente));
            a.setUsuario(em.find(Usuario.class,profissional));
            a.setDataAgendamento(data);
            a.setHoraAtendimento(LocalTime.of(hora,0));
            a.setTurnoAgendamento(TurnoEnum.MANHA);
            a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
            a.registrarUnidadeDeOrigem(a.getUsuario());
            em.persist(a);em.flush();
            return new Reserva(a.getId(),a.getVersion());
        });
        var resultado=como(email,()->app.getBean(FrequenciaIndividualLocalEnsaioService.class)
                .registrar(r.id(),r.versao(),FrequenciaAssistencial.FALTOU));
        return new Reserva(r.id(),resultado.versaoFato());
    }
    public static UUID resolver(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,
            Reserva a,Reserva b,boolean rollback) {
        return resolver(app,email,paciente,vinculo,versao,a,b,rollback,null);
    }
    public static UUID resolver(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,
            Reserva a,Reserva b,boolean rollback,UUID ticket) {
        return como(email,()->tx(app).execute(t->{
            var id=app.getBean(PreparacaoResolucaoFrequenciaLocal.class).registrar(paciente,vinculo,versao,
                    new PlanoResolucaoFrequencia.Referencia(ATENDIMENTO_INDIVIDUAL,a.id(),a.versao()),
                    new PlanoResolucaoFrequencia.Referencia(ATENDIMENTO_INDIVIDUAL,b.id(),b.versao()),
                    "Atendimento sintético registrado em duplicidade.",ticket);
            if(rollback)t.setRollbackOnly();
            return id;
        }));
    }
    public static UUID desfazer(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,UUID resolucao) {
        return como(email,()->tx(app).execute(t->app.getBean(PreparacaoResolucaoFrequenciaLocal.class)
                .desfazer(paciente,vinculo,versao,resolucao,"Conferência sintética: os atendimentos eram distintos.")));
    }
    public static UUID solicitar(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,Reserva r) {
        return como(email,()->app.getBean(RevisaoProvenienciaLocalEnsaioService.class).solicitar(paciente,vinculo,versao,
                com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro.AGENDAMENTO,r.id(),
                "Solicitação sintética de revisão de duplicidade."));
    }
    public static UUID aprovar(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,UUID ticket) {
        return como(email,()->app.getBean(RevisaoProvenienciaLocalEnsaioService.class).decidir(paciente,vinculo,versao,ticket,
                com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao.APROVAR,
                "Aprovação sintética para conferência do atendimento."));
    }
    public static UUID aplicarOrigem(ApplicationContext app,String email,UUID paciente,UUID vinculo,Integer versao,
            UUID ticket,boolean rollback) {
        return como(email,()->tx(app).execute(t->{
            var id=app.getBean(AplicacaoOrigemHistoricaLocal.class).aplicar(paciente,vinculo,versao,ticket,
                    "Aplicação sintética após conferência da fonte assistencial.");
            if(rollback) t.setRollbackOnly();
            return id;
        }));
    }
    public static void corrigir(ApplicationContext app,String email,Reserva r) {
        como(email,()->app.getBean(FrequenciaIndividualLocalEnsaioService.class)
                .corrigir(r.id(),r.versao(),FrequenciaAssistencial.PRESENTE,"Correção sintética de frequência conferida."));
    }
}

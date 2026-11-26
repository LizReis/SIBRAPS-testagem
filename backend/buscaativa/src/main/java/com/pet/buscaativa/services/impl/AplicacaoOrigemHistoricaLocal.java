package com.pet.buscaativa.services.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.migration.AplicacaoOrigemSchemaGuard;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.CandidatoOrigem;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.ProvaOrigem;
import lombok.RequiredArgsConstructor;

/** Escritor interno. Não expor rota antes de integrar todos os consumidores. */
@Component
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
class AplicacaoOrigemHistoricaLocal {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final CoordenacaoEscritaClinicaEnsaio coordenacao;
    private final VerificacaoFatoRevisaoProvenienciaService verificador;
    private final PoliticaJustificativaCorrecao justificativas;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HistoricoFrequenciaLocalEnsaio historicoFrequencia;
    private final jakarta.persistence.EntityManager em;
    private final com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaComandoRepository pendencias;

    private record Aprovacao(UUID decisao,ProvaOrigem prova,OffsetDateTime decididaEm) {}
    private record AplicacaoAnterior(Long historico,OffsetDateTime registradaEm) {}

    UUID aplicar(UUID paciente,UUID vinculo,Integer versao,UUID ticket,String justificativa) {
        var contexto=atores.autorizar(true);
        var bloqueados=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=bloqueados.vinculos().get(paciente);
        var ator=bloqueados.atores().ator();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao,local.getVersion());
        if(!AplicacaoOrigemSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de aplicação de origem não instalada.");
        // Localizar o ticket antes da prova, para não diferenciar casos de outra unidade.
        long historico=historicoDoTicket(local,ticket);
        var aprovacao=aprovacao(local,ticket,historico);
        if(substituida(ticket)) throw new ConflictException("Solicitação substituída por nova rodada de análise.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_aplicacao_origem_historica where pendencia_id=?)
                """,Boolean.class,ticket)) || possuiAplicacaoAtiva(historico))
            throw new ConflictException("Origem histórica já possui aplicação registrada.");
        // A identidade está travada: revalidar a fonte e a cadeia, sem confiar
        // em dados enviados pelo cliente ou na aprovação isoladamente.
        var atual=verificador.verificar(paciente,TipoRegistro.HISTORICO,historico);
        if(!atual.historicoSemOrigemComFonteLocal() || !aprovacao.prova().equals(atual.provaOrigem()))
            throw new ConflictException("Evidência da aprovação desatualizada; origem não corrigida.");
        String motivo=justificativas.validar(justificativa);
        var instante=OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
        if(instante.isBefore(aprovacao.decididaEm()))
            throw new ConflictException("Instante de aplicação anterior à aprovação.");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into tb_aplicacao_origem_historica
                    (id,decisao_id,pendencia_id,vinculo_id,paciente_id,unidade_chave,historico_id,ator_id,perfil,justificativa,registrado_em)
                values (?,?,?,?,?,?,?,?,'ADMINISTRADOR',?,?)
                """,id,aprovacao.decisao(),ticket,local.getId(),local.getPaciente().getId(),
                local.getUnidadeChave(),historico,ator.getId(),motivo,instante);
        // O reconhecimento não cria frequência. Reaproveita apenas a
        // numeração derivada dos fatos comprovados, dentro da transação.
        historicoFrequencia.renumerar(local);
        return id;
    }

    private long historicoDoTicket(com.pet.buscaativa.entities.VinculoAssistencial local,UUID ticket) {
        var registros=jdbc.query("""
                select registro_id from tb_pendencia_revisao_proveniencia
                where id=? and vinculo_id=? and paciente_id=? and unidade_chave=?
                  and tipo_registro='HISTORICO' and situacao_evidencia='REVISAR_ORIGEM_DESCONHECIDA'
                """,(rs,n)->rs.getLong(1),ticket,local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
        if(registros.size()!=1) throw new ResourceNotFoundException("Pendência não encontrada.");
        return registros.getFirst();
    }

    private Aprovacao aprovacao(com.pet.buscaativa.entities.VinculoAssistencial local,UUID ticket,long historico) {
        return jdbc.query("""
                select p.decisao_id,p.fonte,p.fonte_id,p.versao_fonte,p.participante_id,p.responsavel_id,
                       p.ocorrido_em,p.frequencia,p.tipo_historico,d.decidida_em
                from tb_prova_aprovacao_origem p join tb_decisao_revisao_proveniencia d
                    on d.id=p.decisao_id and d.pendencia_id=p.pendencia_id
                    and d.paciente_id=p.paciente_id and d.unidade_chave=p.unidade_chave and d.decisao='APROVAR'
                where p.pendencia_id=? and p.vinculo_id=? and p.paciente_id=? and p.unidade_chave=? and p.historico_id=?
                """,(rs,n)->new Aprovacao(rs.getObject(1,UUID.class),new ProvaOrigem(
                        new CandidatoOrigem(historico,TipoRegistro.valueOf(rs.getString(2)),rs.getLong(3),
                            rs.getObject(7,LocalDateTime.class),FrequenciaAssistencial.valueOf(rs.getString(8))),
                        rs.getInt(4),rs.getObject(5,Long.class),rs.getLong(6),
                        TipoEventoHistoricoPaciente.valueOf(rs.getString(9))),rs.getObject(10,OffsetDateTime.class)),
                ticket,local.getId(),local.getPaciente().getId(),local.getUnidadeChave(),historico)
                .stream().findFirst().orElseThrow(()->new ConflictException("Pendência sem aprovação de origem comprovada."));
    }

    private boolean substituida(UUID ticket) {
        return com.pet.buscaativa.migration.ReaberturaOrigemSchemaGuard.instalada(jdbc)
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from tb_reabertura_origem_historica where anterior_id=?)
                        """,Boolean.class,ticket));
    }

    private boolean possuiAplicacaoAtiva(long historico) {
        String sql="select exists(select 1 from tb_aplicacao_origem_historica a where a.historico_id=?";
        if(com.pet.buscaativa.migration.ReaberturaOrigemSchemaGuard.instalada(jdbc))
            sql+=" and not exists(select 1 from tb_reversao_origem_historica r where r.aplicacao_id=a.id and r.registrado_em>=a.registrado_em)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql+")",Boolean.class,historico));
    }

    UUID reabrir(UUID paciente,UUID vinculo,Integer versao,UUID ticket,String justificativa) {
        var contexto=atores.autorizar(true);
        var bloqueados=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=bloqueados.vinculos().get(paciente);
        var ator=bloqueados.atores().ator();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao,local.getVersion());
        if(!com.pet.buscaativa.migration.ReaberturaOrigemSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de reabertura de origem não instalada.");
        long historico=historicoDoTicket(local,ticket);
        var anterior=aprovacao(local,ticket,historico);
        if(substituida(ticket)) throw new ConflictException("Solicitação já possui nova rodada de análise.");
        if(possuiAplicacaoAtiva(historico))
            throw new ConflictException("Desfaça a aplicação anterior antes de reabrir.");
        var atual=verificador.verificar(paciente,TipoRegistro.HISTORICO,historico);
        if(!atual.historicoSemOrigemComFonteLocal() || atual.provaOrigem()==null)
            throw new ResourceNotFoundException("Registro não encontrado.");
        boolean revertida=Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_aplicacao_origem_historica a
                    join tb_reversao_origem_historica r on r.aplicacao_id=a.id
                    where a.pendencia_id=? and a.vinculo_id=? and a.paciente_id=? and a.unidade_chave=?
                      and r.registrado_em>=a.registrado_em)
                """,Boolean.class,ticket,local.getId(),local.getPaciente().getId(),local.getUnidadeChave()));
        if(!revertida && anterior.prova().equals(atual.provaOrigem()))
            throw new ConflictException("Aprovação permanece válida; não cabe reabertura.");
        // A nova solicitação recebe identidade e auditoria próprias. O serviço
        // V13 exigirá nova decisão e armazenará nova prova V27.
        var motivo=justificativas.validar(justificativa);
        var instante=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(instante.isBefore(anterior.decididaEm().toInstant()))
            throw new ConflictException("Instante de reabertura anterior à aprovação.");
        var nova=com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.paraHistoricoSemOrigemComFonteLocal(
                local,historico,motivo,ator,instante);
        pendencias.save(nova);
        em.flush();
        jdbc.update("""
                insert into tb_reabertura_origem_historica
                (anterior_id,novo_id,vinculo_id,paciente_id,unidade_chave,historico_id,tipo_registro,situacao_evidencia,motivo)
                values (?,?,?,?,?,?,'HISTORICO','REVISAR_ORIGEM_DESCONHECIDA',?)
                """,ticket,nova.getId(),local.getId(),local.getPaciente().getId(),local.getUnidadeChave(),
                historico,revertida?"APLICACAO_REVERTIDA":"APROVACAO_DESATUALIZADA");
        return nova.getId();
    }

    UUID desfazer(UUID paciente,UUID vinculo,Integer versao,UUID aplicacao,String justificativa) {
        var contexto=atores.autorizar(true);
        var bloqueados=coordenacao.paraVinculo(contexto,paciente,vinculo,null);
        var local=bloqueados.vinculos().get(paciente);
        var ator=bloqueados.atores().ator();
        CoordenacaoEscritaClinicaEnsaio.validarVersao(versao,local.getVersion());
        if(!com.pet.buscaativa.migration.ReversaoOrigemSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de reversão de origem não instalada.");
        // A reversão retira o reconhecimento, não corrige a fonte. É permitida
        // mesmo se a aplicação já estiver inconclusiva, sem revalidar a origem.
        var anterior=jdbc.query("""
                select historico_id,registrado_em from tb_aplicacao_origem_historica
                where id=? and vinculo_id=? and paciente_id=? and unidade_chave=?
                """,(rs,n)->new AplicacaoAnterior(rs.getLong(1),rs.getObject(2,OffsetDateTime.class)),
                aplicacao,local.getId(),local.getPaciente().getId(),local.getUnidadeChave())
                .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Aplicação não encontrada."));
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tb_reversao_origem_historica where aplicacao_id=?)
                """,Boolean.class,aplicacao))) throw new ConflictException("Aplicação de origem já revertida.");
        String motivo=justificativas.validar(justificativa);
        var instante=OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
        if(instante.isBefore(anterior.registradaEm()))
            throw new ConflictException("Instante de reversão anterior à aplicação.");
        var id=UUID.randomUUID();
        jdbc.update("""
                insert into tb_reversao_origem_historica
                (id,aplicacao_id,vinculo_id,paciente_id,unidade_chave,historico_id,ator_id,perfil,justificativa,registrado_em)
                values (?,?,?,?,?,?,?,'ADMINISTRADOR',?,?)
                """,id,aplicacao,local.getId(),local.getPaciente().getId(),local.getUnidadeChave(),
                anterior.historico(),ator.getId(),motivo,instante);
        return id;
    }
}

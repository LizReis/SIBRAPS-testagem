package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.EvidenciaOrigemSchemaGuard;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;

/** Interface administrativa local. Não aceitar escopo nem evidência do cliente. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class RevisaoOrigemLocalService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final AplicacaoOrigemHistoricaLocal escritor;
    private final OrigemHistoricaEfetivaLocal origemEfetiva;
    private final JdbcTemplate jdbc;

    public record Evento(UUID id,UUID ator,String perfil,String justificativa,OffsetDateTime registradoEm) {}
    public record Decisao(Evento evento,String resultado) {}
    public record Prova(String fonte,Long registroFonte,Integer versaoFonte,Long participante,
            LocalDateTime ocorridoEm,String frequencia,String tipoHistorico) {}
    public record Reabertura(UUID anterior,UUID nova,String motivo) {}
    public record Auditoria(Integer versaoVinculo,Long historico,Evento solicitacao,Decisao decisao,
            Prova prova,Evento aplicacao,Evento reversao,Reabertura reaberturaAnterior,
            Reabertura reaberturaPosterior,String estadoAplicacao,boolean integridadeEventos) {}
    private record Solicitacao(Long historico,Evento evento) {}

    @Transactional
    public UUID aplicar(UUID paciente,UUID vinculo,Integer versao,UUID ticket,String justificativa) {
        return escritor.aplicar(paciente,vinculo,versao,ticket,justificativa);
    }
    @Transactional
    public UUID desfazer(UUID paciente,UUID vinculo,Integer versao,UUID aplicacao,String justificativa) {
        return escritor.desfazer(paciente,vinculo,versao,aplicacao,justificativa);
    }
    @Transactional
    public UUID reabrir(UUID paciente,UUID vinculo,Integer versao,UUID ticket,String justificativa) {
        return escritor.reabrir(paciente,vinculo,versao,ticket,justificativa);
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public Auditoria auditoria(UUID paciente,UUID vinculo,UUID ticket) {
        atores.autorizar(true);
        var local=autorizacao.exigirVinculoIdentificado(paciente,vinculo);
        if(!ProjecaoClinicaVinculoService.chaveCanonica(local))
            throw new ResourceNotFoundException("Paciente não encontrado.");
        if(!EvidenciaOrigemSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de evidência de origem não instalada.");
        Object[] args={ticket,local.getId(),local.getPaciente().getId(),local.getUnidadeChave()};
        var solicitacao=jdbc.query("""
                select p.registro_id,p.id,u.id_publico,p.perfil_solicitante,p.justificativa,p.solicitado_em
                from tb_pendencia_revisao_proveniencia p join tb_usuario u on u.id=p.solicitante_id
                where p.id=? and p.vinculo_id=? and p.paciente_id=? and p.unidade_chave=?
                    and p.tipo_registro='HISTORICO' and p.situacao_evidencia='REVISAR_ORIGEM_DESCONHECIDA'
                """,(rs,n)->new Solicitacao(rs.getLong(1),new Evento(rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class))),args)
                .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Pendência não encontrada."));
        var decisao=jdbc.query("""
                select d.id,u.id_publico,d.perfil_revisor,d.justificativa,d.decidida_em,d.decisao
                from tb_decisao_revisao_proveniencia d
                join tb_pendencia_revisao_proveniencia p on p.id=d.pendencia_id
                    and p.paciente_id=d.paciente_id and p.unidade_chave=d.unidade_chave
                join tb_usuario u on u.id=d.revisor_id
                where p.id=? and p.vinculo_id=? and p.paciente_id=? and p.unidade_chave=?
                """,(rs,n)->new Decisao(evento(rs),rs.getString(6)),args).stream().findFirst().orElse(null);
        var prova=jdbc.query("""
                select fonte,fonte_id,versao_fonte,participante_id,ocorrido_em,frequencia,tipo_historico
                from tb_prova_aprovacao_origem
                where pendencia_id=? and vinculo_id=? and paciente_id=? and unidade_chave=?
                """,(rs,n)->new Prova(rs.getString(1),rs.getLong(2),rs.getInt(3),rs.getObject(4,Long.class),
                        rs.getObject(5,LocalDateTime.class),rs.getString(6),rs.getString(7)),args).stream().findFirst().orElse(null);
        var aplicacao=jdbc.query("""
                select a.id,u.id_publico,a.perfil,a.justificativa,a.registrado_em
                from tb_aplicacao_origem_historica a join tb_usuario u on u.id=a.ator_id
                where a.pendencia_id=? and a.vinculo_id=? and a.paciente_id=? and a.unidade_chave=?
                """,(rs,n)->evento(rs),args).stream().findFirst().orElse(null);
        var reversao=jdbc.query("""
                select r.id,u.id_publico,r.perfil,r.justificativa,r.registrado_em
                from tb_aplicacao_origem_historica a join tb_reversao_origem_historica r
                    on r.aplicacao_id=a.id and r.vinculo_id=a.vinculo_id and r.paciente_id=a.paciente_id
                    and r.unidade_chave=a.unidade_chave and r.historico_id=a.historico_id
                join tb_usuario u on u.id=r.ator_id
                where a.pendencia_id=? and a.vinculo_id=? and a.paciente_id=? and a.unidade_chave=?
                """,(rs,n)->evento(rs),args).stream().findFirst().orElse(null);
        var anterior=relacao("novo_id",args);
        var posterior=relacao("anterior_id",args);
        boolean integra=!origemEfetiva.inconsistentes(local).contains(solicitacao.historico());
        var reconhecimento=origemEfetiva.consultarPorTicket(local).get(ticket);
        // Retornar registros históricos como trilha, não como prova de
        // validade. Uma evidência divergente nunca certifica a aplicação.
        String estado=!integra?"INCONCLUSIVA":aplicacao==null?"SEM_APLICACAO":
                reconhecimento==null?"INCONCLUSIVA":reconhecimento.reversao()!=null?"REVERTIDA":
                reconhecimento.comprovada()?"ORIGEM_RECONHECIDA":"INCONCLUSIVA";
        return new Auditoria(local.getVersion(),solicitacao.historico(),solicitacao.evento(),decisao,
                prova,aplicacao,reversao,anterior,posterior,estado,integra);
    }
    private Reabertura relacao(String coluna,Object[] args) {
        return jdbc.query("select anterior_id,novo_id,motivo from tb_reabertura_origem_historica where "+coluna+
                "=? and vinculo_id=? and paciente_id=? and unidade_chave=?",
                (rs,n)->new Reabertura(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),args)
                .stream().findFirst().orElse(null);
    }
    private static Evento evento(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Evento(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getObject(5,OffsetDateTime.class));
    }
}

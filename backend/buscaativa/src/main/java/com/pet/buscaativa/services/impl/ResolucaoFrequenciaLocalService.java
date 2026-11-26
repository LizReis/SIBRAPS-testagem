package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.EvidenciaResolucaoSchemaGuard;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte;
import lombok.RequiredArgsConstructor;

/** Contrato local: leitura serializável; escrita delegada ao escritor coordenado. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class ResolucaoFrequenciaLocalService {
    private final AtoresEstadoClinicoLocalEnsaio atores;
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final EscopoUnidadeService escopo;
    private final LinhaDoTempoFrequenciasVinculoService linha;
    private final PreparacaoResolucaoFrequenciaLocal escritor;
    private final JdbcTemplate jdbc;

    public record Referencia(Fonte fonte,Long id,Integer versao) {}
    public record Fato(Referencia referencia,boolean presente,boolean podeDesconsiderar) {}
    public record Candidato(LocalDateTime ocorridoEm,DiagnosticoFaltasConsecutivasVinculoService.TipoCandidato tipo,
            List<Fato> fatos) {}
    public record Candidatos(Integer versaoVinculo,boolean revisaoNecessaria,List<Candidato> candidatos,
            long total,int pagina,int tamanho) {}
    public record Registro(UUID id,Fonte fonteValida,Long fatoValido,Integer versaoValida,String frequenciaValida,
            Fonte fonteDesconsiderada,Long fatoDesconsiderado,Integer versaoDesconsiderada,String frequenciaDesconsiderada,
            LocalDateTime horario,UUID ator,String perfil,String justificativa,OffsetDateTime registradoEm,
            UUID ticket,UUID decisao,UUID reversao,UUID atorReversao,String perfilReversao,String justificativaReversao,
            OffsetDateTime revertidoEm) {}
    public record Auditoria(Integer versaoVinculo,List<Registro> registros,long total,int pagina,int tamanho) {}

    private VinculoAssistencial exigir(UUID paciente,UUID vinculo) {
        var local=autorizacao.exigirVinculoIdentificado(paciente,vinculo);
        if(!ProjecaoClinicaVinculoService.chaveCanonica(local))
            throw new ResourceNotFoundException("Paciente não encontrado.");
        if(!EvidenciaResolucaoSchemaGuard.instalada(jdbc))
            throw new ConflictException("Estrutura de resolução não instalada.");
        return local;
    }
    private static void validarPagina(int pagina,int tamanho) {
        if(pagina<0 || tamanho<1 || tamanho>100) throw new ValidationException("Paginação inválida.");
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public Candidatos candidatos(UUID paciente,UUID vinculo,int pagina,int tamanho) {
        var contexto=atores.autorizar(false);
        var local=exigir(paciente,vinculo);
        validarPagina(pagina,tamanho);
        var ator=escopo.usuarioLogado();
        var bruto=linha.examinar(local,true);
        var efetivo=linha.examinarEfetivo(local,true);
        var diagnostico=efetivo.diagnostico();
        var autorizados=new java.util.HashMap<ProjecaoResolucaoFrequencia.Chave,Fato>();
        var bases=jdbc.query("""
                select fonte_valida,fato_valido from vw_resolucao_frequencia_ativa
                where vinculo_id=? and paciente_id=? and unidade_chave=?
                """,(rs,n)->new ProjecaoResolucaoFrequencia.Chave(Fonte.valueOf(rs.getString(1)),rs.getLong(2)),
                local.getId(),local.getPaciente().getId(),local.getUnidadeChave());
        var protegidos=java.util.Set.copyOf(bases);
        var eventos=new ArrayList<LinhaDoTempoFrequenciasVinculoService.Evento>();
        for(var evento:diagnostico.eventosComOrigemLocal()) {
            var f=PreparacaoResolucaoFrequenciaLocal.carregar(bruto,
                    new PlanoResolucaoFrequencia.Referencia(evento.fonte(),evento.registroId(),null));
            if(contexto.perfil()!=TipoUsuario.ADMINISTRADOR && !Objects.equals(ator.getId(),f.responsavel())) continue;
            if(f.responsavel()==null) continue;
            var chave=new ProjecaoResolucaoFrequencia.Chave(evento.fonte(),evento.registroId());
            var r=f.referencia();
            if(r.versao()==null) continue;
            autorizados.put(chave,new Fato(new Referencia(r.fonte(),r.id(),r.versao()),evento.presente(),
                    !protegidos.contains(chave)));
            eventos.add(evento);
        }
        var localDiagnostico=new LinhaDoTempoFrequenciasVinculoService.Diagnostico(eventos,0,0,0,0);
        var candidatos=DiagnosticoFaltasConsecutivasVinculoService.candidatosReconciliacao(localDiagnostico).stream()
                .map(c->new Candidato(c.ocorridoEm(),c.tipo(),c.fatos().stream().map(e->autorizados.get(
                        new ProjecaoResolucaoFrequencia.Chave(e.fonte(),e.registroId()))).toList()))
                .toList();
        return new Candidatos(local.getVersion(),DiagnosticoFaltasConsecutivasVinculoService.calcular(diagnostico)
                .revisaoHistoricaNecessaria(),candidatos.stream().skip((long)pagina*tamanho).limit(tamanho).toList(),
                candidatos.size(),pagina,tamanho);
    }

    @Transactional(readOnly=true,isolation=Isolation.SERIALIZABLE)
    public Auditoria auditoria(UUID paciente,UUID vinculo,int pagina,int tamanho) {
        var contexto=atores.autorizar(false);
        var local=exigir(paciente,vinculo);
        validarPagina(pagina,tamanho);
        var ator=escopo.usuarioLogado();
        String filtro=" where r.vinculo_id=? and r.paciente_id=? and r.unidade_chave=?";
        var args=new ArrayList<Object>(List.of(local.getId(),local.getPaciente().getId(),local.getUnidadeChave()));
        if(contexto.perfil()!=TipoUsuario.ADMINISTRADOR) {
            filtro+=" and r.responsavel_valido=? and r.responsavel_desconsiderado=?";
            args.add(ator.getId());args.add(ator.getId());
        }
        long total=jdbc.queryForObject("select count(*) from tb_resolucao_frequencia_local r"+filtro,Long.class,args.toArray());
        args.add(tamanho);args.add((long)pagina*tamanho);
        var registros=jdbc.query("""
                select r.id,r.fonte_valida,r.fato_valido,r.versao_valida,r.frequencia_valida,
                    r.fonte_desconsiderada,r.fato_desconsiderado,r.versao_desconsiderada,r.frequencia_desconsiderada,
                    r.horario,u.id_publico,r.perfil,r.justificativa,r.registrado_em,
                    a.pendencia_id,a.decisao_id,d.id,du.id_publico,d.perfil,d.justificativa,d.registrado_em
                from tb_resolucao_frequencia_local r join tb_usuario u on u.id=r.ator_id
                left join tb_reversao_resolucao_frequencia d on d.resolucao_id=r.id and d.vinculo_id=r.vinculo_id
                    and d.paciente_id=r.paciente_id and d.unidade_chave=r.unidade_chave
                left join tb_usuario du on du.id=d.ator_id
                left join tb_aplicacao_pendencia_resolucao a on a.resolucao_id=r.id and a.vinculo_id=r.vinculo_id
                    and a.paciente_id=r.paciente_id and a.unidade_chave=r.unidade_chave
                """+filtro+" order by r.registrado_em desc,r.id desc limit ? offset ?",
                (rs,n)->new Registro(rs.getObject(1,UUID.class),Fonte.valueOf(rs.getString(2)),rs.getLong(3),rs.getInt(4),rs.getString(5),
                        Fonte.valueOf(rs.getString(6)),rs.getLong(7),rs.getInt(8),rs.getString(9),rs.getObject(10,LocalDateTime.class),
                        rs.getObject(11,UUID.class),rs.getString(12),rs.getString(13),rs.getObject(14,OffsetDateTime.class),
                        rs.getObject(15,UUID.class),rs.getObject(16,UUID.class),rs.getObject(17,UUID.class),rs.getObject(18,UUID.class),
                        rs.getString(19),rs.getString(20),rs.getObject(21,OffsetDateTime.class)),args.toArray());
        return new Auditoria(local.getVersion(),List.copyOf(registros),total,pagina,tamanho);
    }

    @Transactional
    public UUID resolver(UUID paciente,UUID vinculo,Integer versao,Referencia valido,Referencia desconsiderado,
            String justificativa,UUID ticket) {
        return escritor.registrar(paciente,vinculo,versao,referencia(valido),referencia(desconsiderado),justificativa,ticket);
    }

    @Transactional
    public UUID desfazer(UUID paciente,UUID vinculo,Integer versao,UUID resolucao,String justificativa) {
        return escritor.desfazer(paciente,vinculo,versao,resolucao,justificativa);
    }
    private static PlanoResolucaoFrequencia.Referencia referencia(Referencia r) {
        if(r==null || r.fonte()==null || r.id()==null || r.id()<=0 || r.versao()==null || r.versao()<0)
            throw new ValidationException("Informe a fonte, o registro e sua versão.");
        return new PlanoResolucaoFrequencia.Referencia(r.fonte(),r.id(),r.versao());
    }
}

package com.pet.buscaativa.entities.dto;
import java.time.Instant;
import java.util.UUID;
import com.pet.buscaativa.entities.AuditoriaBuscaAtiva;
import com.pet.buscaativa.entities.enums.*;

public record AuditoriaBuscaAtivaDTO(UUID id,UUID pacienteId,UUID usuarioId,TipoUsuario perfil,String unidade,
        TipoEventoBuscaAtiva tipoEvento,UUID idRequisicao,Instant ocorridoEm,String situacao,ClassificacaoRisco risco,
        boolean gatilhoConfirmado,boolean indicadoresPendentes,boolean situacaoRua,int quantidade,String destino,String descricao) {
    public AuditoriaBuscaAtivaDTO(AuditoriaBuscaAtiva e){this(e.getId(),e.getPaciente()==null?null:e.getPaciente().getIdPublico(),e.getAtor().getIdPublico(),e.getPerfil(),e.getUnidade(),
        e.getTipoEvento(),e.getChaveRequisicao(),e.getOcorridoEm(),e.getSituacao(),e.getRisco(),e.isGatilhoConfirmado(),e.isIndicadoresPendentes(),e.isSituacaoRua(),e.getQuantidade(),e.getDestino(),e.getDescricao());}
}

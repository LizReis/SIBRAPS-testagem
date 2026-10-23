package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.exception.RevisionDoesNotExistException;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.EvolucaoMensalDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.PainelHistoricoService;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PainelHistoricoServiceImpl implements PainelHistoricoService {
    private static final DateTimeFormatter ROTULO = DateTimeFormatter.ofPattern("MMM/yy", Locale.forLanguageTag("pt-BR"));
    private final EntityManager entityManager;
    private final EscopoUnidadeService escopoUnidadeService;

    @Override
    @Transactional(readOnly = true)
    public List<EvolucaoMensalDTO> reconstruir(List<YearMonth> meses, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua) {
        AuditReader reader = AuditReaderFactory.get(entityManager);
        escopoUnidadeService.validarUnidadeConfigurada();
        return meses.stream().map(mes -> reconstruirMes(reader, mes, unidade, tipoAcompanhamento, situacaoRua)).toList();
    }

    @SuppressWarnings("unchecked")
    private EvolucaoMensalDTO reconstruirMes(AuditReader reader, YearMonth mes, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua) {
        LocalDateTime fim = mes.atEndOfMonth().atTime(23, 59, 59);
        Number revisao;
        try {
            revisao = reader.getRevisionNumberForDate(Date.from(fim.atZone(ZoneId.systemDefault()).toInstant()));
        } catch (RevisionDoesNotExistException ex) {
            return new EvolucaoMensalDTO(mes.toString(), formatarRotulo(mes), 0, 0, 0, false);
        }
        List<Paciente> pacientes = reader.createQuery()
                .forEntitiesAtRevision(Paciente.class, revisao)
                .add(AuditEntity.property("statusPaciente").eq(StatusPaciente.ATIVO))
                .add(escopoUnidadeService.criterioPacienteAuditoria())
                .getResultList();
        long verdes = pacientes.stream().filter(p -> correspondeAosFiltros(p, unidade, tipoAcompanhamento, situacaoRua))
                .filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.VERDE).count();
        long amarelos = pacientes.stream().filter(p -> correspondeAosFiltros(p, unidade, tipoAcompanhamento, situacaoRua))
                .filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.AMARELO).count();
        long vermelhos = pacientes.stream().filter(p -> correspondeAosFiltros(p, unidade, tipoAcompanhamento, situacaoRua))
                .filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO).count();
        return new EvolucaoMensalDTO(mes.toString(), formatarRotulo(mes), verdes, amarelos, vermelhos, true);
    }

    private String formatarRotulo(YearMonth mes) {
        String rotulo = mes.format(ROTULO);
        return rotulo.substring(0, 1).toUpperCase(Locale.forLanguageTag("pt-BR")) + rotulo.substring(1);
    }

    @Override
    public boolean correspondeAosFiltros(Paciente p, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua) {
        if (tipoAcompanhamento != null && p.getTipoAcompanhamento() != tipoAcompanhamento) return false;
        if (situacaoRua != null && p.isSituacaoRua() != situacaoRua) return false;
        if (unidade == null || unidade.isBlank()) return true;
        if (unidade.startsWith("USF:")) {
            return p.getUsfReferencia() != null && unidade.substring(4).equals(p.getUsfReferencia().getCnes());
        }
        return unidade.startsWith("CAPS:") && p.getCapsReferencia() != null
                && unidade.substring(5).equals(p.getCapsReferencia().name());
    }
}

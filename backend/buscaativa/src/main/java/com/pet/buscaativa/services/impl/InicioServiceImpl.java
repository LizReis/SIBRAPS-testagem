package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.dto.PacientePrioritarioDTO;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Agendamento;
import org.springframework.data.domain.Sort;
import com.pet.buscaativa.entities.dto.ResumoInicioDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.InicioService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InicioServiceImpl implements InicioService {

    private final PacienteRepository pacienteRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final EscopoUnidadeService escopoUnidadeService;

    @Override
    @Transactional(readOnly = true)
    public ResumoInicioDTO buscarResumo() {
        LocalDate hoje = LocalDate.now();
        LocalDate seteDiasAtras = hoje.minusDays(6);

        var pacientesAtivos = escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("statusPaciente"), StatusPaciente.ATIVO));
        long totalAtivos = pacienteRepository.count(pacientesAtivos);

        long agendamentosHoje = agendamentoRepository.count(escopoUnidadeService.<Agendamento>specificationPaciente("paciente")
                .and((r,q,cb)->cb.and(cb.equal(r.get("dataAgendamento"), hoje),
                        cb.equal(r.get("situacaoAtendimento"), SituacaoAtendimento.AGENDADO))));

        long faltasUltimosSeteDias = agendamentoRepository.count(escopoUnidadeService.<Agendamento>specificationPaciente("paciente")
                .and((r,q,cb)->cb.and(cb.equal(r.get("situacaoAtendimento"), SituacaoAtendimento.FALTOU),
                        cb.between(r.get("dataAgendamento"), seteDiasAtras, hoje))));

        var atencao = pacientesAtivos.and((r,q,cb)->r.get("classificacaoRisco")
                .in(List.of(ClassificacaoRisco.AMARELO, ClassificacaoRisco.VERMELHO)));
        long pacientesAtencao = pacienteRepository.count(atencao);

        List<PacientePrioritarioDTO> prioritarios = pacienteRepository
                .findAll(atencao, PageRequest.of(0, 5, Sort.by(Sort.Order.desc("classificacaoRisco"),
                        Sort.Order.desc("countFaltas"), Sort.Order.asc("nome")))).getContent()
                .stream()
                .map(p -> new PacientePrioritarioDTO(
                        p.getIdPublico(),
                        p.getNome(),
                        p.getClassificacaoRisco(),
                        p.getCountFaltas(),
                        p.getDataUltimaPresenca()))
                .toList();

        return new ResumoInicioDTO(
                totalAtivos,
                agendamentosHoje,
                faltasUltimosSeteDias,
                pacientesAtencao,
                pacientesAtencao,
                prioritarios);
    }
}
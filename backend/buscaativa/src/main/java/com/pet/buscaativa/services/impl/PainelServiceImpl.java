package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.DistribuicaoClassificacaoDTO;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.PacientePainelDTO;
import com.pet.buscaativa.entities.dto.PainelBuscaAtivaDTO.UnidadePainelDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.PainelHistoricoService;
import com.pet.buscaativa.services.PainelService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PainelServiceImpl implements PainelService {
    private static final List<Integer> PERIODOS_VALIDOS = List.of(3, 6, 12);
    private final PacienteRepository pacienteRepository;
    private final PainelHistoricoService historicoService;
    private final EscopoUnidadeService escopoUnidadeService;

    @Override
    @Transactional(readOnly = true)
    public PainelBuscaAtivaDTO buscarResumo(int periodoMeses, String unidade,
            TipoAcompanhamento tipoAcompanhamento, Boolean situacaoRua) {
        if (!PERIODOS_VALIDOS.contains(periodoMeses)) periodoMeses = 6;

        List<Paciente> ativosSemFiltro = pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("statusPaciente"),StatusPaciente.ATIVO)));
        List<UnidadePainelDTO> unidades = unidadesDisponiveis();
        List<Paciente> ativos = ativosSemFiltro.stream()
                .filter(p -> historicoService.correspondeAosFiltros(p, unidade, tipoAcompanhamento, situacaoRua)).toList();

        long verdes = contar(ativos, ClassificacaoRisco.VERDE);
        long amarelos = contar(ativos, ClassificacaoRisco.AMARELO);
        long vermelhos = contar(ativos, ClassificacaoRisco.VERMELHO);
        long total = ativos.size();
        DistribuicaoClassificacaoDTO distribuicao = new DistribuicaoClassificacaoDTO(
                verdes, amarelos, vermelhos, percentual(verdes, total), percentual(amarelos, total),
                percentual(vermelhos, total));

        Comparator<Paciente> prioridade = Comparator
                .comparingInt((Paciente p) -> p.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Paciente::getCountFaltas).reversed())
                .thenComparing(Paciente::getNome, String.CASE_INSENSITIVE_ORDER);
        List<PacientePainelDTO> prioritarios = ativos.stream()
                .filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO
                        || p.getClassificacaoRisco() == ClassificacaoRisco.AMARELO)
                .sorted(prioridade).limit(5).map(this::paraPrioritario).toList();

        YearMonth atual = YearMonth.now();
        List<YearMonth> meses = new ArrayList<>();
        for (int i = periodoMeses - 1; i >= 0; i--) meses.add(atual.minusMonths(i));
        var evolucao = historicoService.reconstruir(meses, unidade, tipoAcompanhamento, situacaoRua);

        return new PainelBuscaAtivaDTO(total, distribuicao, evolucao, prioritarios, unidades,
                evolucao.stream().allMatch(e -> e.disponivel()));
    }

    private long contar(List<Paciente> pacientes, ClassificacaoRisco classificacao) {
        return pacientes.stream().filter(p -> p.getClassificacaoRisco() == classificacao).count();
    }

    private double percentual(long quantidade, long total) {
        return total == 0 ? 0 : Math.round(quantidade * 1000.0 / total) / 10.0;
    }

    private PacientePainelDTO paraPrioritario(Paciente p) {
        String acao = "Acompanhamento prioritário";
        if (Boolean.TRUE.equals(p.getGatilhoVisitaAcionado())) {
            acao = p.isSituacaoRua() ? "Busca em ponto de referência" : "Visita domiciliar necessária";
        }
        Integer idade = p.getDataNascimento() == null ? null
                : java.time.Period.between(p.getDataNascimento(), LocalDate.now()).getYears();
        return new PacientePainelDTO(p.getIdPublico(), p.getNome(), idade, p.getClassificacaoRisco(),
                p.getCountFaltas(), acao);
    }

    private List<UnidadePainelDTO> unidadesDisponiveis() {
        return List.of(escopoUnidadeService.unidadePainel());
    }
}

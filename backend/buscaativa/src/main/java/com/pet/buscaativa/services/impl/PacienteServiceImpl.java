package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.pet.buscaativa.entities.Endereco;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.mapping.PacienteMapper;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoParticipanteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.HistoricoPacienteService;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.PoliticaClassificacaoPaciente;
import com.pet.buscaativa.services.exceptions.DatabaseException;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.utils.DocumentoUtil;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PacienteServiceImpl implements PacienteService{

    
    private static final int FALTAS_AMARELO = 2;
    private static final int FALTAS_VERMELHO = 3;

    private final PacienteRepository pacienteRepository;
    private final PacienteMapper pacienteMapper;
    private final HistoricoPacienteService historicoPacienteService;
    private final AgendamentoRepository agendamentoRepository;
    private final PoliticaClassificacaoPaciente politicaClassificacao;
    private final SessaoGrupoParticipanteRepository participanteGrupoRepository;
    private final Clock clock;

    private final UsuarioRepository usuarioRepository;
    private final EscopoUnidadeService escopoUnidadeService;


    @Override
    @Transactional
    public PacienteDTO save(PacienteDTO pacienteDTO, boolean ignorarSimilaridade) {
        if (pacienteDTO.countFaltas() != null) {
            throw new ValidationException("O contador de faltas é calculado pelo sistema e não pode ser informado.");
        }
        
        escopoUnidadeService.validarUnidadeConfigurada();
        Paciente pacienteSalvar = null;

        if(pacienteDTO.idPublico() != null){
            pacienteSalvar = pacienteRepository.findByIdPublico(pacienteDTO.idPublico())
                    .filter(escopoUnidadeService::pacientePertenceAoEscopo)
                    .orElseThrow(() -> new DatabaseException("Paciente não encontrado!"));
        }

        // Valida duplicidade antes de alterar a entidade gerenciada, como no fluxo existente.
        validarPacienteDuplicado(pacienteDTO, ignorarSimilaridade);

        if(pacienteSalvar != null){
            pacienteMapper.updatePacienteFromDTO(pacienteDTO, pacienteSalvar);
        }else{
            pacienteSalvar = pacienteMapper.toPacienteEntity(pacienteDTO);

            pacienteSalvar.setStatusPaciente(StatusPaciente.ATIVO);
        }

        escopoUnidadeService.validarAcesso(pacienteSalvar);

        // O papel é permissão, não profissão: apenas RECEPCAO é inelegível.
        pacienteSalvar.setProfissionalReferencia(resolverProfissionalReferencia(pacienteDTO.profissionalReferenciaId()));

        pacienteSalvar.setCpf(
        DocumentoUtil.normalizarCPF(pacienteDTO.cpf()));

        pacienteSalvar.setCns(
        DocumentoUtil.normalizarCNS(pacienteDTO.cns()));


        pacienteSalvar = pacienteRepository.save(pacienteSalvar);

        return pacienteMapper.toPacienteDTO(pacienteSalvar);

    }

    private Usuario resolverProfissionalReferencia(UUID idPublico) {
        if (idPublico == null) return null;
        Usuario usuario = usuarioRepository.findByIdPublico(idPublico)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário de referência não encontrado."));
        if (usuario.getTipoUsuario() == TipoUsuario.RECEPCAO) {
            throw new ValidationException("Usuários do perfil Recepção não podem ser definidos como profissional de referência.");
        }
        return usuario;
    }

    @Override
    public void inativarPaciente(UUID idPublico) {
        Paciente paciente = pacienteRepository.findByIdPublico(idPublico)
                .filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new DatabaseException("Paciente não encontrado!"));

        paciente.setStatusPaciente(StatusPaciente.INATIVO);

        pacienteRepository.save(paciente);
        historicoPacienteService.registrarSituacaoAtual(paciente, "Situação atualizada para INATIVO.");
    }

    @Override
    public PacienteDTO findById(UUID idPublico) {
        Paciente paciente = pacienteRepository.findByIdPublico(idPublico)
                .filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException(idPublico));
        return new PacienteDTO(paciente);
    }

    @Override
    public PacienteListaResponseDTO pesquisar(String q, ClassificacaoRisco classificacao, StatusPaciente status,
            TipoAcompanhamento tipo, int page, int size) {
        size = Math.min(Math.max(size, 1), 50); page = Math.max(page, 0);
        Specification<Paciente> spec = escopoUnidadeService.specificationPaciente();
        if (q != null && !q.isBlank()) {
            String termo=q.trim(), digitos=termo.replaceAll("\\D", "");
            if ((!digitos.isEmpty() && digitos.length()!=11 && digitos.length()!=15) || (digitos.isEmpty() && termo.length()<3))
                throw new ValidationException("Busca deve ter ao menos 3 letras, 11 dígitos de CPF ou 15 de CNS.");
            spec=spec.and((root,cq,cb) -> digitos.length()==11 ? cb.equal(root.get("cpf"),digitos)
                    : digitos.length()==15 ? cb.equal(root.get("cns"),digitos)
                    : cb.like(cb.lower(root.get("nome")), "%"+termo.toLowerCase()+"%"));
        }
        if (classificacao!=null) spec=spec.and((r,c,b)->b.equal(r.get("classificacaoRisco"),classificacao));
        if (status!=null) spec=spec.and((r,c,b)->b.equal(r.get("statusPaciente"),status));
        if (tipo!=null) spec=spec.and((r,c,b)->b.equal(r.get("tipoAcompanhamento"),tipo));
        Sort prioridade=Sort.by(Sort.Order.desc("classificacaoRisco"), Sort.Order.desc("countFaltas"), Sort.Order.asc("nome"));
        var resultado=pacienteRepository.findAll(spec, PageRequest.of(page,size,prioridade));
        var ativos=pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,c,b)->b.equal(r.get("statusPaciente"),StatusPaciente.ATIVO)));
        var resumo=new PacienteResumoDTO(ativos.size(), ativos.stream().filter(p->p.getClassificacaoRisco()==ClassificacaoRisco.AMARELO).count(),
                ativos.stream().filter(p->p.getClassificacaoRisco()==ClassificacaoRisco.VERMELHO).count(),
                ativos.stream().filter(p->politicaClassificacao.semPresencaRecente(p, LocalDate.now())).count());
        return new PacienteListaResponseDTO(resumo, resultado.map(PacienteListaDTO::new).getContent(), page,size,
                resultado.getTotalElements(), resultado.getTotalPages());
    }

    @Override
    public PacienteDetalheDTO findDetalhe(UUID idPublico) {
        Paciente p=pacienteRepository.findByIdPublico(idPublico).filter(escopoUnidadeService::pacientePertenceAoEscopo).orElseThrow(()->new ResourceNotFoundException(idPublico));
        var proximo=agendamentoRepository.findFirstByPacienteAndDataAgendamentoGreaterThanEqualAndSituacaoAtendimentoInOrderByDataAgendamentoAscHoraAtendimentoAsc(
                p, LocalDate.now(), List.of(SituacaoAtendimento.AGENDADO, SituacaoAtendimento.REMARCADO))
                .map(a->new ProximoAgendamentoDTO(a.getId(),a.getDataAgendamento(),a.getHoraAtendimento(),a.getSituacaoAtendimento())).orElse(null);
        String atencao=p.getStatusPaciente()!=StatusPaciente.ATIVO ? "Acompanhamento "+p.getStatusPaciente().name().toLowerCase().replace('_',' ')
                : p.getClassificacaoRisco()==ClassificacaoRisco.VERMELHO ? "Busca ativa recomendada"
                : p.getClassificacaoRisco()==ClassificacaoRisco.AMARELO ? "Atenção necessária" : "Acompanhamento regular";
        return new PacienteDetalheDTO(new PacienteDTO(p),p.getClassificacaoRisco(),proximo,atencao);
    }

    @Override
    public List<AgendamentoDTO> listarAgendamentos(UUID idPublico) {
        Paciente p=pacienteRepository.findByIdPublico(idPublico).filter(escopoUnidadeService::pacientePertenceAoEscopo).orElseThrow(()->new ResourceNotFoundException(idPublico));
        return agendamentoRepository.findDetalhesByPaciente(p).stream().map(a->new AgendamentoDTO(a.getId(),a.getUsuario().getIdPublico(),
                a.getUsuario().getNome(),p.getIdPublico(),p.getNome(),p.getTipoAcompanhamento(),a.getDataAgendamento(),
                a.getTurnoAgendamento(),a.getHoraAtendimento(),a.getSituacaoAtendimento(),
                a.getAgendamentoOriginal()==null?null:a.getAgendamentoOriginal().getId(),a.getVersion())).toList();
    }

    @Override
    public List<PacienteDTO> findAll() {
        List<Paciente> list = pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente());
        return list.stream()
                .map(PacienteDTO::new)
                .toList();
    }
    @Override
    public void validarPacienteDuplicado(
            PacienteDTO pacienteDTO,
            boolean ignorarSimilaridade
    ) {
        boolean possuiDoc = false;

        if (pacienteDTO.cpf() != null && !pacienteDTO.cpf().isBlank()) {
            possuiDoc = true;

            String cpfNormalizado =
                    DocumentoUtil.normalizarCPF(pacienteDTO.cpf());

            pacienteRepository.findByCpf(cpfNormalizado).ifPresent(p -> {
                escopoUnidadeService.validarAcesso(p);
                if (!Objects.equals(
                        p.getIdPublico(),
                        pacienteDTO.idPublico()
                )) {
                    throw new RecursoDuplicadoException(
                            "Já existe um paciente com este CPF."
                    );
                }
            });
        }

        if (pacienteDTO.cns() != null && !pacienteDTO.cns().isBlank()) {
            possuiDoc = true;

            String cnsNormalizado =
                    DocumentoUtil.normalizarCNS(pacienteDTO.cns());

            pacienteRepository.findByCns(cnsNormalizado).ifPresent(p -> {
                escopoUnidadeService.validarAcesso(p);
                if (!Objects.equals(
                        p.getIdPublico(),
                        pacienteDTO.idPublico()
                )) {
                    throw new RecursoDuplicadoException(
                            "Já existe um paciente com este CNS."
                    );
                }
            });
        }

        if (!possuiDoc && !ignorarSimilaridade) {
            List<Paciente> similares = pacienteRepository
                    .findByNomeIgnoreCaseAndNomeMaeIgnoreCaseAndDataNascimento(
                            pacienteDTO.nome(),
                            pacienteDTO.nomeMae(),
                            pacienteDTO.dataNascimento()
                    );

            similares.removeIf(p -> Objects.equals(
                    p.getIdPublico(),
                    pacienteDTO.idPublico()
            ));
            similares.removeIf(p -> !escopoUnidadeService.pacientePertenceAoEscopo(p));

            if (!similares.isEmpty()) {
                List<PacienteDTO> similaresDTO = similares.stream()
                        .map(pacienteMapper::toPacienteDTO)
                        .toList();

                throw new DatabaseException(
                        "Registros similares encontrados. " +
                        "Confirme para prosseguir. " +
                        similaresDTO
                );
            }
        }
    }

    @Override
    public PacienteDTO findByCns(String cns) {
        String cnsNormalizado = DocumentoUtil.normalizarCNS(cns);
        Paciente paciente = pacienteRepository.findOne(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("cns"), cnsNormalizado)))
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado com o CNS informado."));
        return pacienteMapper.toPacienteDTO(paciente);

    }

    @Override
    public PacienteDTO findByCpf(String cpf) {
        String cpfNormalizado = DocumentoUtil.normalizarCPF(cpf);
        Paciente paciente = pacienteRepository.findOne(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("cpf"), cpfNormalizado)))
                .orElseThrow(() -> new ResourceNotFoundException("Paciente não encontrado com o CPF informado."));
        return pacienteMapper.toPacienteDTO(paciente);

    }

    @Override
    public List<PacienteDTO> findByNome(String nome) {
        List<Paciente> pacientes = pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.like(cb.lower(r.get("nome")), "%" + nome.toLowerCase() + "%")));
        
        return pacientes.stream()
                .map(pacienteMapper::toPacienteDTO)
                .toList();

    }

    @Override
    public PacienteDTO findByNomeMae(String nomeMae) {
        var paciente = pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("nomeMae"), nomeMae))).stream().findFirst().orElse(null);

        if(paciente == null){
            return null;
        }

        return pacienteMapper.toPacienteDTO(paciente);
    }

    @Override
    public void atualizarAssiduidadePaciente(Paciente paciente, SituacaoAtendimento statusAnterior,
            SituacaoAtendimento novoStatus, LocalDate dataAtendimento) {

        if (novoStatus == SituacaoAtendimento.PRESENTE) paciente.setGatilhoVisitaAcionado(false);
        recalcularAssiduidadePaciente(paciente);
    }

        // Recalcular a partir da fonte evita dupla contagem e torna correções retroativas
        // determinísticas. O agendamento corrente já está alterado no persistence context.
        @Override
    public void recalcularAssiduidadePaciente(Paciente paciente) {
        record Evento(LocalDateTime quando, boolean presente, int origem, long id) {}
        List<Evento> eventos = new java.util.ArrayList<>();
        for (var a : agendamentoRepository.findByPaciente(paciente)) {
            if (a.getSituacaoAtendimento() == SituacaoAtendimento.PRESENTE
                    || a.getSituacaoAtendimento() == SituacaoAtendimento.FALTOU) {
                eventos.add(new Evento(LocalDateTime.of(a.getDataAgendamento(), a.getHoraAtendimento()),
                        a.getSituacaoAtendimento() == SituacaoAtendimento.PRESENTE, 0, a.getId()));
            }
        }
        for (var p : participanteGrupoRepository.findByPaciente(paciente)) {
            if (p.getSessaoGrupo().getStatus() == com.pet.buscaativa.entities.enums.StatusSessaoGrupo.REALIZADA
                    && p.getStatusPresenca() != com.pet.buscaativa.entities.enums.StatusPresencaGrupo.NAO_REGISTRADA) {
                eventos.add(new Evento(LocalDateTime.of(p.getSessaoGrupo().getDataSessao(), p.getSessaoGrupo().getHorario()),
                        p.getStatusPresenca() == com.pet.buscaativa.entities.enums.StatusPresencaGrupo.PRESENTE, 1, p.getId()));
            }
        }
        eventos.sort(java.util.Comparator.comparing(Evento::quando).thenComparingInt(Evento::origem).thenComparingLong(Evento::id));
        int faltas = 0; LocalDate ultima = null;
        for (Evento e : eventos) {
            if (e.presente()) { faltas = 0; ultima = e.quando().toLocalDate(); paciente.setGatilhoVisitaAcionado(false); }
            else faltas++;
        }
        paciente.setCountFaltas(faltas); paciente.setDataUltimaPresenca(ultima);
        calcularEAtualizarRisco(paciente); pacienteRepository.save(paciente);
    }


    @Override
    public void calcularEAtualizarRisco(Paciente paciente) {
        ClassificacaoRisco classificacaoAnterior = paciente.getClassificacaoRisco();
        if(paciente.getStatusPaciente() == StatusPaciente.INATIVO){
            paciente.setClassificacaoRisco(ClassificacaoRisco.VERDE);
            return;
        }

        //Definiçao dos limites padronizados de dias para Vermelho e Amarelo
        //Se o TipoAcompanhamento for Grupo terapeutico o amarelo recebe = 15, se for individual recebe 60
        //Se o TipoAcompanhamento for Grupo terapeutico o vermelho recebe = 30, se for individual recebe 120
        // Os limites são regras fixas do produto; nenhum perfil pode alterá-los.
        boolean acompanhamentoEmGrupo = paciente.getTipoAcompanhamento() == TipoAcompanhamento.GRUPO_TERAPEUTICO
                || paciente.getTipoAcompanhamento() == TipoAcompanhamento.AMBOS;
        int limiteAmareloDias = acompanhamentoEmGrupo ? PoliticaClassificacaoPaciente.DIAS_AMARELO_GRUPO : PoliticaClassificacaoPaciente.DIAS_AMARELO_INDIVIDUAL;
        int limiteVermelhoDias = acompanhamentoEmGrupo ? PoliticaClassificacaoPaciente.DIAS_VERMELHO_GRUPO : PoliticaClassificacaoPaciente.DIAS_VERMELHO_INDIVIDUAL;

        long diasAusente = 0;

        if (paciente.getDataUltimaPresenca() != null) {
            diasAusente = java.time.temporal.ChronoUnit.DAYS.between(paciente.getDataUltimaPresenca(), LocalDate.now(clock));
        }

        // Verifica primeiro o risco máximo (Vermelho)
        int faltasConsecutivas = faltasConsecutivas(paciente);

        if (faltasConsecutivas >= FALTAS_VERMELHO || diasAusente > limiteVermelhoDias) {
            paciente.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);
        }  
        // Depois o risco médio (Amarelo)
        else if (faltasConsecutivas >= FALTAS_AMARELO || diasAusente > limiteAmareloDias) {
            paciente.setClassificacaoRisco(ClassificacaoRisco.AMARELO);
        } 
        // Caso contrário, está tudo bem (Verde)
        else {
            paciente.setClassificacaoRisco(ClassificacaoRisco.VERDE);
        }

        if (paciente.getId() != null && classificacaoAnterior != ClassificacaoRisco.VERMELHO
                && paciente.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO) {
            historicoPacienteService.registrarSituacaoAtual(paciente,
                    "Classificação de risco alterada de " + classificacaoAnterior + " para VERMELHO.");
        }
    }


    @Override
    @Transactional
    public void encerrarAcompanhamento(UUID idPublico, EncerramentoPacienteDTO encerramento) {
        Paciente paciente = pacienteRepository.findByIdPublicoForUpdate(idPublico)
                .filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException(idPublico));

        if (encerramento.motivo() == MotivoEncerramento.OUTRO
                && (encerramento.descricao() == null || encerramento.descricao().isBlank())) {
            throw new ValidationException("Descrição obrigatória quando o motivo do encerramento for OUTRO.");
        }else if (encerramento.statusPaciente() == StatusPaciente.ATIVO) {
            throw new ValidationException("O encerramento não aceita status ATIVO; utilize o fluxo de reativação.");
        } else if(paciente.getStatusPaciente() == encerramento.statusPaciente()){
            throw new ValidationException("Paciente para encerramento não deve ter o mesmo status ou status ATIVO.");
        }

        paciente.setStatusPaciente(encerramento.statusPaciente());
        paciente.setMotivoEncerramento(encerramento.motivo());
        paciente.setDescricaoMotivoEncerramento(encerramento.descricao());
        paciente.setDataEncerramento(LocalDate.now());
        paciente.setProfissionalEncerramento(usuarioAuditoria());
        paciente.setClassificacaoRisco(ClassificacaoRisco.VERDE);
        paciente.setGatilhoVisitaAcionado(false);
        pacienteRepository.save(paciente);
        historicoPacienteService.registrarSituacaoAtual(paciente, "Acompanhamento encerrado. Situação atual: " + paciente.getStatusPaciente() + ".");
    }

    @Override
    @Transactional
    public void reativarAcompanhamento(UUID idPublico, ReativacaoPacienteDTO reativacao) {
        Paciente paciente = pacienteRepository.findByIdPublicoForUpdate(idPublico)
                .filter(escopoUnidadeService::pacientePertenceAoEscopo)
                .orElseThrow(() -> new ResourceNotFoundException(idPublico));


        if (paciente.getStatusPaciente() == StatusPaciente.ATIVO) {
            throw new ValidationException("Paciente já está ativo.");
        }

        paciente.setStatusPaciente(StatusPaciente.ATIVO);
        paciente.setDataReativacao(LocalDate.now());
        paciente.setMotivoReativacao(reativacao.motivo());
        paciente.setProfissionalReativacao(usuarioAuditoria());
        calcularEAtualizarRisco(paciente);
        pacienteRepository.save(paciente);
        historicoPacienteService.registrarSituacaoAtual(paciente, "Acompanhamento reativado. Situação atual: ATIVO.");
    }

    @Override
    @Transactional
    public List<AlertaBuscaAtivaDTO> listarPacientesEmBuscaAtiva() {
        // Reclassificação sob demanda impede que a consulta dependa exclusivamente do scheduler.
        pacienteRepository.findAll(escopoUnidadeService.<Paciente>specificationPaciente()
                .and((r,q,cb)->cb.equal(r.get("statusPaciente"),StatusPaciente.ATIVO))).forEach(p -> {
            calcularEAtualizarRisco(p);
            pacienteRepository.save(p);
        });
        return pacienteRepository
                .findAll(escopoUnidadeService.<Paciente>specificationPaciente().and((r,q,cb)->cb.and(
                        cb.equal(r.get("statusPaciente"),StatusPaciente.ATIVO),
                        cb.equal(r.get("classificacaoRisco"),ClassificacaoRisco.VERMELHO))))
                .stream()
                .map(this::montarAlertaBuscaAtiva)
                .toList();
    }

    private AlertaBuscaAtivaDTO montarAlertaBuscaAtiva(Paciente paciente) {
        boolean alertaDeVisita = Boolean.TRUE.equals(paciente.getGatilhoVisitaAcionado());

        String alerta = null;
        String localBusca = null;
        String avisoAdicional = null;

        if (alertaDeVisita) {
            if (paciente.isSituacaoRua()) {
                alerta = "Busca em ponto de referência necessária";
                localBusca = formatarPontoReferencia(paciente.getEndereco());
                if (localBusca == null) {
                    avisoAdicional = "Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.";
                }
            } else {
                alerta = "Visita domiciliar necessária";
                localBusca = formatarEndereco(paciente.getEndereco());
            }
        }

        return new AlertaBuscaAtivaDTO(
                paciente.getIdPublico(),
                paciente.getNome(),
                paciente.getClassificacaoRisco(),
                faltasConsecutivas(paciente),
                alerta,
                localBusca,
                avisoAdicional);
    }

    private int faltasConsecutivas(Paciente paciente) {
        return paciente.getCountFaltas();
    }

    private String formatarPontoReferencia(Endereco endereco) {
        if (endereco == null || endereco.getComplemento() == null || endereco.getComplemento().isBlank()) {
            return null;
        }
        return endereco.getComplemento();
    }

    private String formatarEndereco(Endereco endereco) {
        if (endereco == null) {
            return null;
        }
        return String.join(", ", List.of(
                valor(endereco.getLogradouro()),
                valor(endereco.getNumero()),
                valor(endereco.getBairro()),
                valor(endereco.getCidade()),
                valor(endereco.getEstado())))
                .replaceAll("(, )+", ", ")
                .replaceAll("^, |, $", "");
    }

    private String valor(String valor) {
        return valor == null ? "" : valor;
    }

    private String usuarioAuditoria() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "SISTEMA";
        }
        return authentication.getName();
    }
    
}

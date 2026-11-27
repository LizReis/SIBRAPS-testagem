import { interpretarErroHttp } from '../../services/erro-http';
import { contratoExperimental } from '../../services/configuracao-contrato';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, Observable, of, throwError } from 'rxjs';
import { GrupoTerapeuticoPayload, GrupoTerapeuticoService, ParticipanteGrupoPayload, ParticipanteSessaoPayload, SessaoGrupoPayload, SessaoInscricaoRetroativaPayload, StatusPresencaGrupo } from '../../services/grupo-terapeutico-service';
import { PacienteLista, PacienteService } from '../../services/paciente/paciente-service';
import { UsuarioLogadoDTO, UsuarioLogadoService } from '../../services/usuario-logado-service';

type Aba = 'visao-geral' | 'participantes' | 'ocorrencia';
type OpcaoOcorrencia = 'ocorreu' | 'cancelada';

@Component({
  selector: 'app-detalhe-grupo-terapeutico',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './detalhe-grupo-terapeutico.html',
  styleUrl: './detalhe-grupo-terapeutico.css',
})
export class DetalheGrupoTerapeutico implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  grupoId!: number;
  sessaoId!: number;
  grupo: GrupoTerapeuticoPayload | null = null;
  sessao: SessaoGrupoPayload | null = null;
  sessoesRetroativas: SessaoInscricaoRetroativaPayload[] = [];
  aba: Aba = 'visao-geral';
  carregando = true;
  salvando = false;
  erro = '';
  sucesso = '';
  usuarioAtual?: UsuarioLogadoDTO;
  sessaoDesatualizada = false;
  novaSessaoAberta = false;
  dataNovaSessao = '';
  horaNovaSessao = '';
  dataCriacaoIncerta?: string;
  removerDaSessao = false;
  buscaParticipante = '';
  termoPaciente = '';
  pacientesEncontrados: PacienteLista[] = [];
  buscandoPacientes = false;
  pacienteSelecionado: PacienteLista | null = null;
  frequenciasRetroativas: Record<number, StatusPresencaGrupo | undefined> = {};
  opcaoOcorrencia: OpcaoOcorrencia = 'ocorreu';
  frequencias: Record<string, StatusPresencaGrupo | undefined> = {};
  motivoCancelamento = '';
  participantesGrupo: ParticipanteGrupoPayload[] = [];
  modoInscricao: 'FUTURA' | 'RETROATIVA' | 'SESSAO' = 'FUTURA';
  participanteParaRemover: ParticipanteGrupoPayload | ParticipanteSessaoPayload | null = null;
  editandoFrequencia = false;
  confirmandoCorrecao = false;

  constructor(private route: ActivatedRoute, private grupos: GrupoTerapeuticoService, private pacientes: PacienteService, private usuario: UsuarioLogadoService, private router: Router) {}

  ngOnInit(): void {
    this.grupoId = Number(this.route.snapshot.paramMap.get('grupoId'));
    this.sessaoId = Number(this.route.snapshot.paramMap.get('sessaoId'));
    this.carregar();
  }

  carregar(): void {
    if (this.salvando) return;
    if (this.sessaoDesatualizada) {
      this.confirmandoCorrecao = false;
      this.editandoFrequencia = false;
      this.participanteParaRemover = null;
    }
    this.carregando = true; this.erro = ''; this.sucesso = '';
    const data = this.route.snapshot.queryParamMap.get('data');
    forkJoin({
      grupos: this.grupos.listarGrupos(),
      usuario: this.usuario.obterUsuarioLogado(),
      retroativas: this.grupos.listarSessoesParaInscricaoRetroativa(this.grupoId).pipe(
        // A recusa de RF20 não impede o acesso de leitura à sessão pelo link.
        catchError(e => interpretarErroHttp(e).tipo === 'ACCESS_DENIED' && data
          ? of([]) : throwError(() => e)),
      ),
    }).subscribe({
      next: ({ grupos, usuario, retroativas }) => {
        this.grupo = grupos.find(g => g.id === this.grupoId) ?? null;
        this.usuarioAtual = usuario;
        if (!this.podeExecutarAcoesClinicas) this.modoInscricao = 'SESSAO';
        this.sessoesRetroativas = retroativas;
        this.carregarParticipantesGrupo();
        const dataSessao = data || retroativas.find(s => s.sessaoId === this.sessaoId)?.data;
        if (!this.grupo) return this.falhar('Grupo terapêutico não encontrado.');
        if (!dataSessao) return this.falhar('Não foi possível localizar a sessão pelo contrato de leitura atual. Volte à lista de grupos e acesse novamente.');
        this.carregarSessao(dataSessao);
      },
      error: e => this.tratarErro(e),
    });
  }

  private carregarSessao(data: string): void {
    this.grupos.listarSessoes(data, data).subscribe({
      next: sessoes => {
        this.sessao = sessoes.find(s => s.id === this.sessaoId && s.grupoId === this.grupoId) ?? null;
        if (!this.sessao) return this.falhar('Sessão não encontrada.');
        this.iniciarRascunho(this.sessao);
        this.sessaoDesatualizada = false;
        this.motivoCancelamento = this.sessao.motivoCancelamento ?? '';
        this.carregando = false;
        this.cdr.markForCheck();
      }, error: e => this.tratarErro(e),
    });
  }

  selecionarAba(aba: Aba): void { this.aba = aba; this.erro = ''; this.sucesso = ''; }
  get participantesFiltrados(): ParticipanteGrupoPayload[] { const q = this.buscaParticipante.trim().toLocaleLowerCase('pt-BR'); return this.participantesGrupo.filter(p => !q || p.nomePaciente.toLocaleLowerCase('pt-BR').includes(q)); }
  get faltas(): number | null {
    return this.sessao?.status === 'REALIZADA' && !this.autoriaInconclusiva
      ? this.sessao.participantes.filter(p => p.statusPresenca === 'FALTOU').length : null;
  }
  get presentes(): number { return Object.values(this.frequencias).filter(v => v === 'PRESENTE').length; }
  get ausentes(): number { return Object.values(this.frequencias).filter(v => v === 'FALTOU').length; }
  get naoMarcados(): number { return (this.sessao?.participantes.length ?? 0) - this.presentes - this.ausentes; }
  get podeExecutarAcoesClinicas(): boolean {
    return this.usuarioAtual?.tipoUsuario === 'ADMINISTRADOR' || this.usuarioAtual?.tipoUsuario === 'PROFISSIONAL';
  }

  get podeGerirGrupo(): boolean {
    return !!this.grupo && !!this.usuarioAtual && (this.usuarioAtual.tipoUsuario !== 'PROFISSIONAL'
      || this.usuarioAtual.idPublico === this.grupo.coordenadorId);
  }

  get podeInscricaoRetroativa(): boolean {
    return this.podeExecutarAcoesClinicas && this.podeGerirGrupo;
  }

  get podeEditarSessao(): boolean {
    return !!this.usuarioAtual && this.sessao?.status === 'AGENDADA' && this.sessao.statusExibicao === 'AGENDADO';
  }

  get autoriaInconclusiva(): boolean {
    return !!this.sessao?.responsabilidadePendente
      || (this.sessao?.status === 'REALIZADA' && !this.sessao.responsavelAtendimentoId);
  }

  get podeCancelarSessao(): boolean {
    return this.podeExecutarAcoesClinicas && this.podeGerirGrupo
      && this.sessao?.status === 'AGENDADA' && !this.autoriaInconclusiva;
  }

  get podeGravarProvisoria(): boolean {
    return this.podeCancelarSessao && this.sessao?.statusExibicao === 'EM_ANDAMENTO';
  }

  get podeConfirmarRealizacao(): boolean {
    return this.podeGravarProvisoria && (!this.sessao?.profissionalDesignadoId
      || this.sessao.profissionalDesignadoId === this.grupo?.coordenadorId);
  }

  get podeCorrigirSessao(): boolean {
    const sessao = this.sessao;
    return !!sessao && sessao.status === 'REALIZADA' && !this.autoriaInconclusiva
      && this.podeExecutarAcoesClinicas && (this.usuarioAtual?.tipoUsuario === 'ADMINISTRADOR'
        || this.usuarioAtual?.idPublico === sessao.responsavelAtendimentoId);
  }

  private iniciarRascunho(sessao: SessaoGrupoPayload): void {
    this.frequencias = Object.fromEntries(sessao.participantes
      .filter(p => p.statusPresenca === 'PRESENTE' || p.statusPresenca === 'FALTOU')
      .map(p => [p.pacienteId, p.statusPresenca!]));
  }

  labelFrequencia(p: ParticipanteSessaoPayload): string {
    if (!p.statusPresenca || this.autoriaInconclusiva) return 'Frequência inconclusiva';
    if (p.statusPresenca === 'NAO_REGISTRADA') return 'Frequência não registrada';
    const valor = p.statusPresenca === 'PRESENTE' ? 'Presente' : 'Faltou';
    return this.sessao?.status === 'REALIZADA' ? `Confirmada: ${valor}` : `Provisória salva: ${valor}`;
  }

  temRascunho(p: ParticipanteSessaoPayload): boolean {
    const frequencia = this.frequencias[p.pacienteId];
    return !!frequencia && frequencia !== p.statusPresenca;
  }

  salvarProvisoria(p: ParticipanteSessaoPayload): void {
    const sessao = this.sessao;
    const status = this.frequencias[p.pacienteId];
    if (!sessao || !status || !this.podeGravarProvisoria || !this.temRascunho(p) || this.salvando || this.sessaoDesatualizada) return;
    this.executarEscrita(this.grupos.registrarProvisoria(sessao.id, p.pacienteId, status, sessao.version), atualizada => {
      this.sessao = atualizada;
      this.sucesso = 'Frequência provisória salva para este participante. A ocorrência segue pendente.';
    });
  }

  criarSessaoAdicional(): void {
    if (!this.grupo?.ativo || !this.usuarioAtual || !this.dataNovaSessao || this.salvando
        || this.sessaoDesatualizada || this.dataCriacaoIncerta) return;
    const data = this.dataNovaSessao;
    this.executarEscrita(this.grupos.criarProximaSessao({
      grupoId: this.grupoId, dataSessao: data, horario: this.horaNovaSessao || null,
    }), sessao => {
      this.novaSessaoAberta = false;
      void this.router.navigate(['/grupos-terapeuticos'], { queryParams: { data: sessao.dataSessao } });
    }, () => this.dataCriacaoIncerta = data);
  }

  conferirSessaoAdicional(): void {
    const data = this.dataCriacaoIncerta;
    if (!data || this.salvando) return;
    this.salvando = true;
    this.grupos.listarSessoes(data, data).subscribe({
      next: sessoes => {
        this.salvando = false;
        this.dataCriacaoIncerta = undefined;
        if (sessoes.some(s => s.grupoId === this.grupoId && s.status !== 'CANCELADA')) {
          void this.router.navigate(['/grupos-terapeuticos'], { queryParams: { data } });
        } else {
          this.sessaoDesatualizada = false;
          this.erro = '';
          this.sucesso = 'Nenhuma sessão ativa localizada nessa data. Confira os dados antes de criar.';
        }
        this.cdr.markForCheck();
      },
      error: e => { this.salvando = false; this.tratarErro(e); },
    });
  }
  get realizadas(): SessaoInscricaoRetroativaPayload[] { return this.sessoesRetroativas.filter(s => s.status === 'REALIZADA'); }
  get pendenciaRetroativa(): boolean { return this.sessoesRetroativas.some(s => s.status === 'AGENDADA' && s.statusExibicao !== 'AGENDADO'); }
  get retroativaInvalida(): boolean { return !this.pacienteSelecionado || this.pendenciaRetroativa || this.sessoesRetroativas.some(s => s.necessitaFrequencia && !this.frequenciasRetroativas[s.sessaoId]); }
  get possuiSessaoRealizada(): boolean { return this.sessoesRetroativas.some(s => s.status === 'REALIZADA'); }

  pesquisarPaciente(): void {
    const termo = this.termoPaciente.trim(); if (!termo) { this.pacientesEncontrados = []; return; }
    this.buscandoPacientes = true; this.erro = '';
    this.pacientes.pesquisar({ busca: termo, pagina: 0, tamanho: 10 }).subscribe({
      next: r => { const inscritos = new Set(this.sessao?.participantes.map(p => p.pacienteId)); this.pacientesEncontrados = r.pacientes.filter(p => p.statusPaciente === 'ATIVO' && !!p.idPublico && (this.modoInscricao !== 'SESSAO' || !inscritos.has(p.idPublico))); this.buscandoPacientes = false; },
      error: e => { this.buscandoPacientes = false; this.tratarErro(e); },
    });
  }
  selecionarPaciente(p: PacienteLista): void { this.pacienteSelecionado = p; this.pacientesEncontrados = []; }
  confirmarInscricao(): void {
    const paciente = this.pacienteSelecionado?.idPublico;
    const sessao = this.sessao;
    if (!paciente || !sessao || this.salvando || this.sessaoDesatualizada) return;
    if (this.modoInscricao === 'SESSAO') {
      if (!this.podeEditarSessao) return;
      this.executarEscrita(this.grupos.adicionarParticipante(sessao.id, paciente, sessao.version), atualizada => {
        this.atualizarComposicao(atualizada);
        this.limparInscricao('Paciente incluído somente nesta sessão.');
      });
      return;
    }
    if (!this.podeExecutarAcoesClinicas) return;
    if (this.modoInscricao === 'RETROATIVA' && (!this.podeInscricaoRetroativa || this.retroativaInvalida)) return;
    const frequenciasPassadas = this.sessoesRetroativas.filter(s => s.necessitaFrequencia)
      .map(s => ({ sessaoId: s.sessaoId, statusPresenca: this.frequenciasRetroativas[s.sessaoId]! }));
    const requisicao = this.modoInscricao === 'RETROATIVA'
      ? this.grupos.inscreverRetroativamente(this.grupoId, { pacienteId: paciente, frequenciasPassadas })
      : this.grupos.inscreverEmSessoesFuturas(this.grupoId, paciente);
    this.executarEscrita(requisicao, () => {
      this.limparInscricao('Inscrição na série registrada.');
      this.carregarParticipantesGrupo();
      this.carregarSessao(sessao.dataSessao);
    });
  }

  private limparInscricao(mensagem: string): void {
    this.pacienteSelecionado = null;
    this.termoPaciente = '';
    this.frequenciasRetroativas = {};
    this.sucesso = mensagem;
  }

  private carregarParticipantesGrupo(): void {
    this.grupos.listarParticipantesDoGrupo(this.grupoId).subscribe({
      next: p => { this.participantesGrupo = p; this.cdr.markForCheck(); },
      error: e => this.tratarErro(e),
    });
  }

  private atualizarComposicao(sessao: SessaoGrupoPayload): void {
    this.sessao = sessao;
    this.iniciarRascunho(sessao);
    this.carregarParticipantesGrupo();
  }

  prepararRemocao(p: ParticipanteGrupoPayload | ParticipanteSessaoPayload, somenteSessao: boolean): void {
    this.erro = '';
    this.participanteParaRemover = p;
    this.removerDaSessao = somenteSessao;
  }

  removerParticipante(): void {
    const participante = this.participanteParaRemover;
    const sessao = this.sessao;
    if (!participante || !sessao || this.salvando || this.sessaoDesatualizada) return;
    if (this.removerDaSessao) {
      if (!this.podeEditarSessao) return;
      this.executarEscrita(this.grupos.removerParticipante(sessao.id, participante.pacienteId, sessao.version), atualizada => {
        this.participanteParaRemover = null;
        this.atualizarComposicao(atualizada);
        this.sucesso = 'Participante removido somente desta sessão.';
      });
      return;
    }
    if (!this.podeExecutarAcoesClinicas || !('possuiInscricaoFutura' in participante) || !participante.possuiInscricaoFutura) return;
    this.executarEscrita(this.grupos.removerParticipanteDoGrupo(this.grupoId, participante.pacienteId), () => {
      this.participanteParaRemover = null;
      this.sucesso = 'Participante removido das próximas sessões.';
      this.carregarParticipantesGrupo();
      this.carregarSessao(sessao.dataSessao);
    });
  }

  iniciarCorrecao(): void {
    if (!this.podeCorrigirSessao || !this.sessao || this.sessaoDesatualizada) return;
    this.editandoFrequencia = true;
    this.iniciarRascunho(this.sessao);
  }

  cancelarCorrecao(): void {
    this.editandoFrequencia = false;
    this.confirmandoCorrecao = false;
    if (this.sessao) this.iniciarRascunho(this.sessao);
  }

  readonly contratoExperimental = contratoExperimental;
  justificativaCorrecao = '';

  solicitarCorrecao(): void {
    this.erro = '';
    this.confirmandoCorrecao = true;
  }

  salvarCorrecoes(): void {
    const sessao = this.sessao;
    const justificativa = this.justificativaCorrecao.trim();
    if (!sessao || !this.podeCorrigirSessao || this.salvando || this.sessaoDesatualizada
        || (contratoExperimental && justificativa.length < 10)) return;
    const alteradas = sessao.participantes.filter(p => this.temRascunho(p)
        && (p.statusPresenca === 'PRESENTE' || p.statusPresenca === 'FALTOU'))
      .map(p => ({ pacienteId: p.pacienteId, statusPresenca: this.frequencias[p.pacienteId]! }));
    if (!alteradas.length) return this.cancelarCorrecao();
    this.executarEscrita(this.grupos.corrigirFrequencias(sessao.id, alteradas, sessao.version,
      contratoExperimental ? justificativa : undefined), atualizada => {
      this.sessao = atualizada;
      this.cancelarCorrecao();
      this.justificativaCorrecao = '';
      this.sucesso = 'Frequências corrigidas com sucesso.';
    });
  }

  confirmarOcorrencia(): void {
    const sessao = this.sessao;
    const ocorreu = this.opcaoOcorrencia === 'ocorreu';
    if (!sessao || this.salvando || this.sessaoDesatualizada
        || !(ocorreu ? this.podeConfirmarRealizacao : this.podeCancelarSessao)) return;
    const frequencias = ocorreu ? sessao.participantes.flatMap(p => {
      const statusPresenca = this.frequencias[p.pacienteId];
      return statusPresenca ? [{ pacienteId: p.pacienteId, statusPresenca }] : [];
    }) : [];
    this.executarEscrita(this.grupos.confirmarOcorrencia(sessao.id, {
      ocorreu, frequencias, motivoCancelamento: ocorreu ? null : (this.motivoCancelamento.trim() || null), version: sessao.version,
    }), atualizada => {
      this.sessao = atualizada;
      this.iniciarRascunho(atualizada);
      this.sucesso = ocorreu ? 'Ocorrência confirmada com sucesso.' : 'Cancelamento confirmado com sucesso.';
    });
  }

  private executarEscrita<T>(requisicao: Observable<T>, sucesso: (valor: T) => void, incerta?: () => void): void {
    this.salvando = true;
    this.erro = '';
    this.sucesso = '';
    requisicao.subscribe({
      next: valor => {
        this.salvando = false;
        sucesso(valor);
        this.cdr.markForCheck();
      },
      error: (e: HttpErrorResponse) => {
        this.salvando = false;
        const falhaIncerta = e.status === 0 || e.status >= 500;
        this.sessaoDesatualizada = falhaIncerta || interpretarErroHttp(e).tipo === 'STALE_VERSION';
        if (falhaIncerta) incerta?.();
        this.tratarErro(e, true);
      },
    });
  }

  statusLabel(): string { return ({ AGENDADO: 'Agendado', EM_ANDAMENTO: 'Em andamento', REALIZADO: 'Realizado', CANCELADO: 'Cancelado' } as const)[this.sessao!.statusExibicao]; }
  recorrenciaLabel(): string { return this.grupo!.recorrencia === 'UNICA' ? 'Única' : ({ SEMANAL: 'Semanal', QUINZENAL: 'Quinzenal', MENSAL: 'Mensal' } as const)[this.grupo!.recorrencia]; }
  formatarData(data: string): string { const [a,m,d] = data.split('-').map(Number); return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(a,m-1,d)); }
  iniciais(nome: string): string { return nome.trim().split(/\s+/).slice(0,2).map(n => n[0]).join('').toUpperCase(); }
  private falhar(mensagem: string): void { this.erro = mensagem; this.carregando = false; }
  private tratarErro(e: HttpErrorResponse, escrita = false): void {
    this.falhar(interpretarErroHttp(e, { escrita }).mensagem);
    this.cdr.markForCheck();
  }
}

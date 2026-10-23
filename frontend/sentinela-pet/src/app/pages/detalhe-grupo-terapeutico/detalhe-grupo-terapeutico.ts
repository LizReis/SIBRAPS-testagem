import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin, of, switchMap } from 'rxjs';
import { GrupoTerapeuticoPayload, GrupoTerapeuticoService, ParticipanteGrupoPayload, SessaoGrupoPayload, SessaoInscricaoRetroativaPayload, StatusPresencaGrupo } from '../../services/grupo-terapeutico-service';
import { PacienteLista, PacienteService } from '../../services/paciente/paciente-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

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
  podeExecutarAcoesClinicas = false;
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
  modoInscricao: 'FUTURA' | 'RETROATIVA' = 'FUTURA';
  participanteParaRemover: ParticipanteGrupoPayload | null = null;
  editandoFrequencia = false;
  confirmandoCorrecao = false;

  constructor(private route: ActivatedRoute, private grupos: GrupoTerapeuticoService, private pacientes: PacienteService, private usuario: UsuarioLogadoService) {}

  ngOnInit(): void {
    this.grupoId = Number(this.route.snapshot.paramMap.get('grupoId'));
    this.sessaoId = Number(this.route.snapshot.paramMap.get('sessaoId'));
    this.carregar();
  }

  carregar(): void {
    this.carregando = true; this.erro = ''; this.sucesso = '';
    const data = this.route.snapshot.queryParamMap.get('data');
    forkJoin({
      grupos: this.grupos.listarGrupos(),
      usuario: this.usuario.obterUsuarioLogado(),
      retroativas: this.grupos.listarSessoesParaInscricaoRetroativa(this.grupoId).pipe(
        // Recepção não possui acesso a RF20; a sessão ainda pode ser carregada pela data do link.
        switchMap(s => of(s)),
      ),
    }).subscribe({
      next: ({ grupos, usuario, retroativas }) => {
        this.grupo = grupos.find(g => g.id === this.grupoId) ?? null;
        this.podeExecutarAcoesClinicas = ['ADMINISTRADOR', 'PROFISSIONAL'].includes(usuario.tipoUsuario);
        this.sessoesRetroativas = retroativas;
        this.carregarParticipantesGrupo();
        const dataSessao = data || retroativas.find(s => s.sessaoId === this.sessaoId)?.data;
        if (!this.grupo) return this.falhar('Grupo terapêutico não encontrado.');
        if (!dataSessao) return this.falhar('Não foi possível localizar a sessão pelo contrato de leitura atual. Volte à lista de grupos e acesse novamente.');
        this.carregarSessao(dataSessao);
      },
      error: (e: HttpErrorResponse) => {
        // RF20 responde 403 para recepção. O carregamento principal continua com a data presente no link.
        if (e.status === 403 && data) {
          this.usuario.obterUsuarioLogado().subscribe(u => {
            this.podeExecutarAcoesClinicas = ['ADMINISTRADOR', 'PROFISSIONAL'].includes(u.tipoUsuario);
            this.grupos.listarGrupos().subscribe(gs => { this.grupo = gs.find(g => g.id === this.grupoId) ?? null; this.carregarParticipantesGrupo(); this.carregarSessao(data); });
          });
        } else this.tratarErro(e);
      },
    });
  }

  private carregarSessao(data: string): void {
    this.grupos.listarSessoes(data, data).subscribe({
      next: sessoes => {
        this.sessao = sessoes.find(s => s.id === this.sessaoId && s.grupoId === this.grupoId) ?? null;
        if (!this.sessao) return this.falhar('Sessão não encontrada.');
        this.sessao.participantes.forEach(p => { if (p.statusPresenca !== 'NAO_REGISTRADA') this.frequencias[p.pacienteId] = p.statusPresenca; });
        this.motivoCancelamento = this.sessao.motivoCancelamento ?? '';
        this.carregando = false;
      }, error: e => this.tratarErro(e),
    });
  }

  selecionarAba(aba: Aba): void { this.aba = aba; this.erro = ''; this.sucesso = ''; }
  get participantesFiltrados(): ParticipanteGrupoPayload[] { const q = this.buscaParticipante.trim().toLocaleLowerCase('pt-BR'); return this.participantesGrupo.filter(p => !q || p.nomePaciente.toLocaleLowerCase('pt-BR').includes(q)); }
  get faltas(): number { return this.sessao?.participantes.filter(p => p.statusPresenca === 'FALTOU').length ?? 0; }
  get presentes(): number { return Object.values(this.frequencias).filter(v => v === 'PRESENTE').length; }
  get ausentes(): number { return Object.values(this.frequencias).filter(v => v === 'FALTOU').length; }
  get naoMarcados(): number { return (this.sessao?.participantes.length ?? 0) - this.presentes - this.ausentes; }
  get somenteLeitura(): boolean { return !this.podeExecutarAcoesClinicas || this.sessao?.status === 'REALIZADA' || this.sessao?.status === 'CANCELADA'; }
  get realizadas(): SessaoInscricaoRetroativaPayload[] { return this.sessoesRetroativas.filter(s => s.status === 'REALIZADA'); }
  get pendenciaRetroativa(): boolean { return this.sessoesRetroativas.some(s => s.status === 'AGENDADA' && s.statusExibicao !== 'AGENDADO'); }
  get retroativaInvalida(): boolean { return !this.pacienteSelecionado || this.pendenciaRetroativa || this.sessoesRetroativas.some(s => s.necessitaFrequencia && !this.frequenciasRetroativas[s.sessaoId]); }
  get possuiSessaoRealizada(): boolean { return this.sessoesRetroativas.some(s => s.status === 'REALIZADA'); }

  pesquisarPaciente(): void {
    const termo = this.termoPaciente.trim(); if (!termo) { this.pacientesEncontrados = []; return; }
    this.buscandoPacientes = true; this.erro = '';
    this.pacientes.pesquisar({ busca: termo, pagina: 0, tamanho: 10 }).subscribe({
      next: r => { const inscritos = new Set(this.sessao?.participantes.map(p => p.pacienteId)); this.pacientesEncontrados = r.pacientes.filter(p => p.statusPaciente === 'ATIVO' && !!p.idPublico && !inscritos.has(p.idPublico)); this.buscandoPacientes = false; },
      error: e => { this.buscandoPacientes = false; this.tratarErro(e); },
    });
  }
  selecionarPaciente(p: PacienteLista): void { this.pacienteSelecionado = p; this.pacientesEncontrados = []; }
  confirmarInscricao(): void {
    if (!this.pacienteSelecionado?.idPublico || (this.modoInscricao === 'RETROATIVA' && this.retroativaInvalida)) return;
    this.salvando = true; this.erro = '';
    const frequenciasPassadas = this.sessoesRetroativas.filter(s => s.necessitaFrequencia).map(s => ({ sessaoId: s.sessaoId, statusPresenca: this.frequenciasRetroativas[s.sessaoId]! }));
    const request$ = this.modoInscricao === 'RETROATIVA' ? this.grupos.inscreverRetroativamente(this.grupoId, { pacienteId: this.pacienteSelecionado.idPublico, frequenciasPassadas })
      : this.grupos.inscreverEmSessoesFuturas(this.grupoId, this.pacienteSelecionado.idPublico);
    request$.subscribe({
      next: () => { this.salvando = false; this.pacienteSelecionado = null; this.termoPaciente = ''; this.frequenciasRetroativas = {}; this.sucesso = 'Paciente inscrito com sucesso.'; this.carregarParticipantesGrupo(); this.carregarSessao(this.sessao!.dataSessao); },
      error: e => { this.salvando = false; this.tratarErro(e); },
    });
  }
  private carregarParticipantesGrupo(): void { this.grupos.listarParticipantesDoGrupo(this.grupoId).subscribe({ next: p => this.participantesGrupo = p, error: e => this.tratarErro(e) }); }
  removerParticipante(): void { if (!this.participanteParaRemover) return; this.salvando = true; this.grupos.removerParticipanteDoGrupo(this.grupoId, this.participanteParaRemover.pacienteId).subscribe({ next: () => { this.salvando = false; this.participanteParaRemover = null; this.sucesso = 'Participante removido das próximas sessões.'; this.carregarParticipantesGrupo(); this.carregarSessao(this.sessao!.dataSessao); }, error: e => { this.salvando = false; this.tratarErro(e); } }); }
  iniciarCorrecao(): void { this.editandoFrequencia = true; this.frequencias = {}; this.sessao?.participantes.forEach(p => this.frequencias[p.pacienteId] = p.statusPresenca); }
  cancelarCorrecao(): void { this.editandoFrequencia = false; this.confirmandoCorrecao = false; this.frequencias = {}; this.sessao?.participantes.forEach(p => this.frequencias[p.pacienteId] = p.statusPresenca); }
  solicitarCorrecao(): void { this.confirmandoCorrecao = true; }
  salvarCorrecoes(): void { if (!this.sessao) return; const alteradas = this.sessao.participantes.filter(p => this.frequencias[p.pacienteId] !== p.statusPresenca).map(p => ({ pacienteId: p.pacienteId, statusPresenca: this.frequencias[p.pacienteId]! })); if (!alteradas.length) return this.cancelarCorrecao(); this.salvando = true; this.grupos.corrigirFrequencias(this.sessao.id, alteradas, this.sessao.version).subscribe({ next: s => { this.sessao = s; this.salvando = false; this.editandoFrequencia = false; this.confirmandoCorrecao = false; this.sucesso = 'Frequências corrigidas com sucesso.'; }, error: e => { this.salvando = false; this.tratarErro(e); } }); }
  confirmarOcorrencia(): void {
    if (!this.sessao || this.somenteLeitura) return;
    this.salvando = true; this.erro = '';
    const ocorreu = this.opcaoOcorrencia === 'ocorreu';
    const frequencias = ocorreu ? Object.entries(this.frequencias).filter(([, status]) => !!status).map(([pacienteId, statusPresenca]) => ({ pacienteId, statusPresenca: statusPresenca! })) : [];
    this.grupos.confirmarOcorrencia(this.sessaoId, { ocorreu, frequencias, motivoCancelamento: ocorreu ? null : (this.motivoCancelamento.trim() || null), version: this.sessao.version }).subscribe({
      next: atualizada => { this.sessao = atualizada; this.salvando = false; this.sucesso = ocorreu ? 'Ocorrência confirmada com sucesso.' : 'Cancelamento confirmado com sucesso.'; },
      error: e => { this.salvando = false; this.tratarErro(e); if (e.status === 409) this.carregarSessao(this.sessao!.dataSessao); },
    });
  }
  statusLabel(): string { return ({ AGENDADO: 'Agendado', EM_ANDAMENTO: 'Em andamento', REALIZADO: 'Realizado', CANCELADO: 'Cancelado' } as const)[this.sessao!.statusExibicao]; }
  recorrenciaLabel(): string { return this.grupo!.recorrencia === 'UNICA' ? 'Única' : ({ SEMANAL: 'Semanal', QUINZENAL: 'Quinzenal', MENSAL: 'Mensal' } as const)[this.grupo!.recorrencia]; }
  formatarData(data: string): string { const [a,m,d] = data.split('-').map(Number); return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(a,m-1,d)); }
  iniciais(nome: string): string { return nome.trim().split(/\s+/).slice(0,2).map(n => n[0]).join('').toUpperCase(); }
  private falhar(mensagem: string): void { this.erro = mensagem; this.carregando = false; }
  private tratarErro(e: HttpErrorResponse): void { const mensagens: Record<number,string> = { 403: 'Você não possui permissão para realizar esta ação.', 404: 'Grupo ou sessão não encontrado.', 409: 'A sessão foi alterada por outro usuário. Atualize os dados antes de continuar.', 422: e.error?.message || 'Verifique os dados informados.' }; this.falhar(mensagens[e.status] || 'Não foi possível concluir a operação. Tente novamente.'); }
}

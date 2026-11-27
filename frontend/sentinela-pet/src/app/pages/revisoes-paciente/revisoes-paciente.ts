import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, Subscription, take } from 'rxjs';
import { ProntuarioLocal } from '../../services/contrato-clinico-local';
import { interpretarErroHttp } from '../../services/erro-http';
import { CandidatoOrigem, RevisaoClinicaService, TipoRegistroRevisao } from '../../services/revisao-clinica-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';

export interface RegistroRevisavel {
  tipo: TipoRegistroRevisao; registro: number; descricao: string; data: string; origem?: CandidatoOrigem;
}

export function registrosParaConferencia(p: ProntuarioLocal): RegistroRevisavel[] {
  const registros: RegistroRevisavel[] = [
    ...p.atendimentos.map(a => ({ tipo: 'AGENDAMENTO' as const, registro: a.id,
      descricao: 'Atendimento individual — ' + (a.frequenciaAssistencial ?? 'Frequência não confirmada'),
      data: [a.dataAgendamento, a.horaAtendimento].filter(Boolean).join(' ') })),
    ...p.sessoes.map(s => ({ tipo: 'SESSAO_GRUPO' as const, registro: s.sessao,
      descricao: s.tema + ' — ' + (s.frequencia ?? 'Frequência não confirmada'),
      data: [s.data, s.hora].filter(Boolean).join(' ') })),
    ...p.historico.map(h => ({ tipo: 'HISTORICO' as const, registro: h.id,
      descricao: h.tipo.replaceAll('_', ' '), data: h.ocorridoEm })),
  ];
  const unicos = new Map<string, RegistroRevisavel>();
  for (const r of registros) {
    if (Number.isSafeInteger(r.registro) && r.registro > 0) unicos.set(r.tipo + ':' + r.registro, r);
  }
  return [...unicos.values()];
}

@Component({
  selector: 'app-revisoes-paciente',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './revisoes-paciente.html',
  styleUrl: '../relatorios/relatorios.css',
})
export class RevisoesPaciente implements OnInit {
  private readonly api = inject(RevisaoClinicaService);
  private readonly usuario = inject(UsuarioLogadoService);
  private readonly route = inject(ActivatedRoute);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  private consulta?: Subscription;
  private consultaOrigem?: Subscription;
  private geracao = 0;
  paciente = '';
  vinculo = '';
  prontuario?: ProntuarioLocal;
  registros: RegistroRevisavel[] = [];
  candidatos: RegistroRevisavel[] = [];
  selecionado?: RegistroRevisavel;
  justificativa = '';
  admin = false;
  autorizado = false;
  carregando = false;
  carregandoOrigem = false;
  origemConsultada = false;
  salvando = false;
  exigeConsulta = true;
  erro = '';
  aviso = '';

  ngOnInit(): void {
    this.usuario.obterUsuarioLogado().pipe(take(1), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: u => {
        this.admin = u.tipoUsuario === 'ADMINISTRADOR';
        this.autorizado = this.admin || u.tipoUsuario === 'PROFISSIONAL';
        if (!this.autorizado) {
          this.erro = 'Perfil sem permissão para solicitar revisão clínica.';
          this.cdr.markForCheck();
          return;
        }
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
          this.paciente = params.get('id') ?? '';
          this.vinculo = params.get('vinculo') ?? '';
          this.salvando = false;
          this.aviso = '';
          this.carregar();
        });
      },
      error: e => {
        this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível conferir a autorização.' }).mensagem;
        this.cdr.markForCheck();
      },
    });
  }

  carregar(): void {
    if (!this.autorizado || this.salvando) return;
    const atual = ++this.geracao;
    this.consulta?.unsubscribe();
    this.consultaOrigem?.unsubscribe();
    this.carregandoOrigem = false;
    this.origemConsultada = false;
    this.prontuario = undefined;
    this.registros = [];
    this.candidatos = [];
    this.selecionado = undefined;
    this.justificativa = '';
    this.exigeConsulta = true;
    this.erro = '';
    if (!this.paciente || !this.vinculo) {
      this.erro = 'Registro não encontrado.';
      this.carregando = false;
      return;
    }
    this.carregando = true;
    this.consulta = this.api.prontuario(this.paciente).pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
      if (atual === this.geracao) { this.carregando = false; this.cdr.markForCheck(); }
    })).subscribe({
      next: p => {
        if (p.paciente.idPublico !== this.paciente || p.paciente.vinculo !== this.vinculo ||
            !Number.isSafeInteger(p.paciente.versao) || p.paciente.versao < 0) {
          this.erro = 'Registro não encontrado.';
          return;
        }
        this.prontuario = p;
        this.registros = registrosParaConferencia(p);
        this.exigeConsulta = false;
      },
      error: e => this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível consultar os registros do vínculo.' }).mensagem,
    });
  }

  consultarOrigem(): void {
    if (!this.admin || !this.prontuario || this.exigeConsulta || this.carregandoOrigem || this.salvando) return;
    const atual = this.geracao;
    this.carregandoOrigem = true;
    this.origemConsultada = false;
    if (this.selecionado?.origem) { this.selecionado = undefined; this.justificativa = ''; }
    this.candidatos = [];
    this.erro = '';
    this.consultaOrigem = this.api.candidatosOrigem(this.paciente, this.vinculo)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        if (atual === this.geracao) { this.carregandoOrigem = false; this.cdr.markForCheck(); }
      })).subscribe({
        next: dados => {
          if (dados.versaoVinculo !== this.prontuario?.paciente.versao) {
            this.exigeConsulta = true;
            this.erro = 'O vínculo foi atualizado. Consulte novamente antes de registrar a solicitação.';
            return;
          }
          this.candidatos = dados.candidatos.map(c => ({ tipo: 'HISTORICO', registro: c.historico,
            descricao: 'Origem histórica — fonte local identificada', data: c.ocorridoEm, origem: c }));
          this.origemConsultada = true;
        },
        error: e => this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível consultar as evidências de origem.' }).mensagem,
      });
  }

  selecionar(r: RegistroRevisavel): void {
    if (this.exigeConsulta || this.salvando || this.carregando || this.carregandoOrigem ||
        !(this.registros.includes(r) || (this.admin && this.candidatos.includes(r)))) return;
    this.selecionado = r;
    this.justificativa = '';
    this.erro = '';
    this.aviso = '';
  }

  get podeSolicitar(): boolean {
    const n = this.justificativa.trim().length;
    return this.autorizado && !!this.prontuario && !!this.selecionado &&
      !this.exigeConsulta && !this.carregando && !this.carregandoOrigem && !this.salvando && n >= 10 && n <= 2000;
  }

  solicitar(): void {
    const registro = this.selecionado;
    const p = this.prontuario;
    if (!this.podeSolicitar || !registro || !p) return;
    const atual = this.geracao;
    this.salvando = true;
    this.erro = '';
    this.aviso = '';
    this.api.solicitar(this.paciente, this.vinculo, p.paciente.versao, registro.tipo, registro.registro, this.justificativa.trim())
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        if (atual === this.geracao) { this.salvando = false; this.cdr.markForCheck(); }
      })).subscribe({
        next: () => {
          if (atual !== this.geracao) return;
          this.exigeConsulta = true;
          this.aviso = 'Solicitação registrada. O atendimento e sua frequência não foram alterados.';
          this.selecionado = undefined;
          this.justificativa = '';
        },
        error: e => {
          if (atual !== this.geracao) return;
          this.exigeConsulta = true;
          this.erro = interpretarErroHttp(e, { escrita: true, padrao: 'Não foi possível registrar a solicitação.' }).mensagem;
        },
      });
  }
}

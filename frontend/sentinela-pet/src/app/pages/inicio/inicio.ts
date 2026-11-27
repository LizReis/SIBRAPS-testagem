import { contratoExperimental } from '../../services/configuracao-contrato';
import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { RouterLink } from '@angular/router';
import { InicioService, ResumoInicioPayload } from '../../services/inicio-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { podeCadastrarPaciente, podeCriarAgendamento, TIPOS_USUARIO } from '../../auth/permissoes';

@Component({
  selector: 'app-inicio',
  imports: [CommonModule, RouterLink],
  templateUrl: './inicio.html',
  styleUrl: './inicio.css',
})
export class Inicio implements OnInit {
  private readonly inicioService = inject(InicioService);

  private readonly destroy = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);
  private consulta?: Subscription;
  readonly contratoExperimental = contratoExperimental;
  resumo?: ResumoInicioPayload;
  carregando = true;
  erro = false;

  readonly indicadores = [
    { titulo: 'Pacientes Cadastrados', descricao: 'Total de pacientes ativos', icone: 'bi-people', campo: 'totalPacientesAtivos', cor: 'blue' },
    { titulo: 'Agendamentos Hoje', descricao: 'Consultas agendadas', icone: 'bi-calendar3', campo: 'totalAgendamentosHoje', cor: 'blue' },
    { titulo: 'Faltas Últimos 7 dias', descricao: contratoExperimental ? 'Faltas individuais comprovadas na agenda' : 'Pacientes faltaram', icone: 'bi-graph-up-arrow', campo: 'totalFaltasUltimosSeteDias', cor: 'green' },
    { titulo: 'Indicadores Atenção', descricao: contratoExperimental ? 'Classificações amarela e vermelha confirmadas' : 'Requerem atenção', icone: 'bi-shield', campo: 'totalPacientesAtencao', cor: 'orange' },
  ] as const;

  private readonly usuario = inject(UsuarioLogadoService);
  private perfil = '';
  private readonly acoes = [
    { titulo: 'Cadastrar paciente', rota: '/pacientes/novo', icone: 'bi-person-plus', permitido: podeCadastrarPaciente },
    { titulo: 'Novo agendamento', rota: '/agenda/novo', icone: 'bi-calendar-plus', permitido: podeCriarAgendamento },
    { titulo: 'Consultar agenda', rota: '/agenda', icone: 'bi-clipboard-check', permitido: (tipo: string) => TIPOS_USUARIO.some(p => p === tipo) },
    { titulo: 'Consultar busca ativa', rota: '/painel', icone: 'bi-geo-alt', permitido: (tipo: string) => TIPOS_USUARIO.some(p => p === tipo) },
  ];

  get acoesRapidas() { return this.acoes.filter(acao => acao.permitido(this.perfil)); }

  ngOnInit(): void {
    this.usuario.obterUsuarioLogado().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: u => { this.perfil = u.tipoUsuario; this.cdr.markForCheck(); },
      error: () => { this.perfil = ''; this.cdr.markForCheck(); },
    });
    this.buscarResumo();
  }

  buscarResumo(): void {
    this.consulta?.unsubscribe();
    this.resumo = undefined;
    this.carregando = true;
    this.erro = false;
    this.consulta = this.inicioService.buscarResumo().pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (resumo) => {
        this.resumo = resumo;
        this.carregando = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.resumo = undefined;
        this.carregando = false;
        this.erro = true;
        this.cdr.markForCheck();
      },
    });
  }

  valorIndicador(campo: keyof Pick<ResumoInicioPayload, 'totalPacientesAtivos' | 'totalAgendamentosHoje' | 'totalFaltasUltimosSeteDias' | 'totalPacientesAtencao'>): number | string {
    return this.resumo ? this.resumo[campo] ?? 'Não confirmado' : '—';
  }
}
  
import { contratoExperimental } from '../../services/configuracao-contrato';
import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { RouterLink } from '@angular/router';
import { InicioService, ResumoInicioPayload } from '../../services/inicio-service';

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
    { titulo: 'Pacientes Cadastrados', descricao: 'Total de pacientes ativos', icone: 'groups', campo: 'totalPacientesAtivos', cor: 'blue' },
    { titulo: 'Agendamentos Hoje', descricao: 'Consultas agendadas', icone: 'calendar_month', campo: 'totalAgendamentosHoje', cor: 'blue' },
    { titulo: 'Faltas Últimos 7 dias', descricao: contratoExperimental ? 'Faltas individuais comprovadas na agenda' : 'Pacientes faltaram', icone: 'trending_up', campo: 'totalFaltasUltimosSeteDias', cor: 'green' },
    { titulo: 'Indicadores Atenção', descricao: contratoExperimental ? 'Classificações amarela e vermelha confirmadas' : 'Requerem atenção', icone: 'shield', campo: 'totalPacientesAtencao', cor: 'orange' },
  ] as const;

  readonly acoesRapidas = [
    { titulo: 'Cadastrar paciente', rota: '/pacientes/novo', icone: 'person_add' },
    { titulo: 'Novo agendamento', rota: '/agenda/novo', icone: 'edit_calendar' },
    { titulo: 'Registrar presença', rota: '/agenda', icone: 'fact_check' },
    { titulo: 'Registrar busca ativa', rota: '/pacientes', icone: 'location_on' },
  ];

  ngOnInit(): void {
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
  
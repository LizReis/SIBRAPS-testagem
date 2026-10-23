import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
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

  resumo?: ResumoInicioPayload;
  carregando = true;
  erro = false;

  readonly indicadores = [
    { titulo: 'Pacientes Cadastrados', descricao: 'Total de pacientes ativos', icone: 'groups', campo: 'totalPacientesAtivos', cor: 'blue' },
    { titulo: 'Agendamentos Hoje', descricao: 'Consultas agendadas', icone: 'calendar_month', campo: 'totalAgendamentosHoje', cor: 'blue' },
    { titulo: 'Faltas Últimos 7 dias', descricao: 'Pacientes faltaram', icone: 'trending_up', campo: 'totalFaltasUltimosSeteDias', cor: 'green' },
    { titulo: 'Indicadores Atenção', descricao: 'Requerem atenção', icone: 'shield', campo: 'totalPacientesAtencao', cor: 'orange' },
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
    this.carregando = true;
    this.erro = false;
    this.inicioService.buscarResumo().subscribe({
      next: (resumo) => {
        this.resumo = resumo;
        this.carregando = false;
      },
      error: () => {
        this.carregando = false;
        this.erro = true;
      },
    });
  }

  valorIndicador(campo: keyof Pick<ResumoInicioPayload, 'totalPacientesAtivos' | 'totalAgendamentosHoje' | 'totalFaltasUltimosSeteDias' | 'totalPacientesAtencao'>): number | string {
    return this.resumo?.[campo] ?? '—';
  }
}
  
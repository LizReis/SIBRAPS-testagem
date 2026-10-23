import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  AgendamentoPaciente,
  PacienteDetalhe,
  PacienteService,
} from '../../services/paciente/paciente-service';

@Component({
  selector: 'app-agendamentos-paciente',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './agendamentos-paciente.html',
  styleUrl: './agendamentos-paciente.css',
})
export class AgendamentosPaciente implements OnInit {
  id = '';
  detalhe?: PacienteDetalhe;
  agendamentos: AgendamentoPaciente[] = [];
  carregando = true;
  erro = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly pacienteService: PacienteService,
  ) {}

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';

    if (!this.id) {
      this.erro = 'Paciente não informado.';
      this.carregando = false;
      return;
    }

    this.carregar();
  }

  carregar(): void {
    this.carregando = true;
    this.erro = '';

    forkJoin({
      detalhe: this.pacienteService.buscarDetalhe(this.id),
      agendamentos: this.pacienteService.listarAgendamentos(this.id),
    }).subscribe({
      next: ({ detalhe, agendamentos }) => {
        this.detalhe = detalhe;
        this.agendamentos = this.ordenar(agendamentos);
        this.carregando = false;
      },
      error: () => {
        this.erro =
          'Não foi possível carregar os agendamentos deste paciente.';
        this.carregando = false;
      },
    });
  }

  iniciais(): string {
    return (this.detalhe?.paciente.nome ?? '')
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((parte) => parte.charAt(0))
      .join('')
      .toUpperCase();
  }

  label(valor?: string): string {
    return (valor ?? 'Não informado')
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (letra) => letra.toUpperCase());
  }

  private ordenar(
    agendamentos: AgendamentoPaciente[],
  ): AgendamentoPaciente[] {
    const agora = Date.now();

    const instante = (item: AgendamentoPaciente) =>
      new Date(
        `${item.dataAgendamento}T${item.horaAtendimento}`,
      ).getTime();

    return [...agendamentos].sort((a, b) => {
      const aFuturo = instante(a) >= agora;
      const bFuturo = instante(b) >= agora;

      if (aFuturo !== bFuturo) {
        return aFuturo ? -1 : 1;
      }

      return aFuturo
        ? instante(a) - instante(b)
        : instante(b) - instante(a);
    });
  }
}
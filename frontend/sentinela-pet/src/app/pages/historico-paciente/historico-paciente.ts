import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import {
  HistoricoPacienteEvento,
  HistoricoPacientePayload,
  PacienteService,
} from '../../services/paciente/paciente-service';

@Component({
  selector: 'app-historico-paciente',
  standalone: true,
  imports: [CommonModule, RouterLink],
  providers: [DatePipe],
  templateUrl: './historico-paciente.html',
  styleUrl: './historico-paciente.css',
})
export class HistoricoPaciente implements OnInit {
  historico: HistoricoPacientePayload | null = null;
  idPublico: string | null = null;
  carregando = false;
  erro: string | null = null;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly pacienteService: PacienteService,
    private readonly datePipe: DatePipe,
  ) {}

  ngOnInit(): void {
    this.idPublico = this.route.snapshot.paramMap.get('id');
    if (!this.idPublico) {
      this.erro = 'Paciente não informado.';
      return;
    }
    this.carregarHistorico();
  }

  carregarHistorico(): void {
    if (!this.idPublico) return;
    this.carregando = true;
    this.erro = null;
    this.pacienteService.buscarHistorico(this.idPublico).subscribe({
      next: (historico) => {
        this.historico = { ...historico, eventos: historico.eventos ?? [] };
        this.carregando = false;
      },
      error: () => {
        this.erro = 'Não foi possível carregar o histórico do paciente.';
        this.carregando = false;
      },
    });
  }

  voltarParaDetalhes(): void {
    if (this.idPublico) this.router.navigate(['/pacientes/detalhes', this.idPublico]);
  }

  formatarEnum(valor?: string): string {
    if (!valor) return 'Não informado';
    return valor.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letra) => letra.toUpperCase());
  }

  formatarDataHora(data?: string, formato = 'dd/MM/yyyy'): string {
    return data ? (this.datePipe.transform(data, formato) ?? 'Não informado') : 'Não informado';
  }

  classeEvento(evento: HistoricoPacienteEvento): string {
    return `evento-${evento.tipo.toLowerCase().replaceAll('_', '-')}`;
  }
  
  iniciais(): string {
    return (this.historico?.nomePaciente ?? '')
      .trim().split(/\s+/).slice(0, 2)
      .map((parte) => parte.charAt(0)).join('').toUpperCase();
  }
}
import { CommonModule, DatePipe } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, finalize } from 'rxjs';
import { interpretarErroHttp } from '../../services/erro-http';
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
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroy = inject(DestroyRef);
  private consulta?: Subscription;
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
    this.consulta?.unsubscribe();
    this.historico = null;
    this.carregando = true;
    this.erro = null;
    this.consulta = this.pacienteService.buscarHistorico(this.idPublico)
      .pipe(takeUntilDestroyed(this.destroy), finalize(() => {
        this.carregando = false;
        this.cdr.markForCheck();
      })).subscribe({
        next: (historico) => {
          this.historico = { ...historico, eventos: historico.eventos ?? [] };
        },
        error: e => {
          this.erro = interpretarErroHttp(e, { padrao: 'Não foi possível carregar o histórico do paciente.' }).mensagem;
        },
      });
  }

  ultimaPresenca(h: HistoricoPacientePayload): string {
    if (h.ultimaPresencaConclusiva === false) return 'Não confirmada';
    if (h.dataUltimaPresenca) return this.formatarDataHora(h.dataUltimaPresenca);
    return h.ultimaPresencaConclusiva === true ? 'Nenhuma presença comprovada' : 'Não informada';
  }

  voltarParaDetalhes(): void {
    if (this.idPublico) this.router.navigate(['/pacientes/detalhes', this.idPublico]);
  }

  formatarEnum(valor?: string | null): string {
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
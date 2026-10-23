import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { PacienteService } from '../../services/paciente/paciente-service';
import { HistoricoPaciente } from './historico-paciente';

describe('HistoricoPaciente', () => {
  let fixture: ComponentFixture<HistoricoPaciente>;
  const navigate = vi.fn();
  const buscarHistorico = vi.fn();

  beforeEach(async () => {
    buscarHistorico.mockClear();
    navigate.mockClear();
    buscarHistorico.mockReturnValue(of({
      idPublico: 'paciente-publico', nomePaciente: 'Paciente', statusPaciente: 'ATIVO',
      classificacaoAtual: 'VERDE', tipoAcompanhamento: 'INDIVIDUAL', quantidadeFaltasAtual: 0,
      totalConsultasAgendadas: 0, totalPresencas: 0, totalFaltas: 0, totalRemarcacoes: 0,
      totalGruposTerapeuticos: 0, totalRegistrosBuscaAtiva: 0, eventos: [],
    }));
    await TestBed.configureTestingModule({
      imports: [HistoricoPaciente],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'paciente-publico' } } } },
        { provide: Router, useValue: { navigate } },
        { provide: PacienteService, useValue: { buscarHistorico } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HistoricoPaciente);
  });

  it('carrega o histórico usando o idPublico da rota', () => {
    fixture.detectChanges();
    expect(buscarHistorico).toHaveBeenCalledWith('paciente-publico');
    expect(fixture.nativeElement.textContent).toContain('Nenhum evento foi registrado');
  });

  it('exibe erro e permite tentar novamente', () => {
    buscarHistorico.mockReturnValueOnce(throwError(() => new Error('falha')));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar');
    fixture.nativeElement.querySelector('.btn-primario').click();
    expect(buscarHistorico).toHaveBeenCalledTimes(2);
  });
});
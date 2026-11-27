import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PacienteSelecao, PacienteService } from '../../services/paciente/paciente-service';
import { ProfissionalService } from '../../services/profissional/profissional-service';
import { AgendamentoService } from '../../services/agendamento-service';

import { NovoAgendamento } from './novo-agendamento';

describe('NovoAgendamento', () => {
  let component: NovoAgendamento;
  let fixture: ComponentFixture<NovoAgendamento>;
  const criarAgendamento = vi.fn();

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NovoAgendamento],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: PacienteService, useValue: {} },
        { provide: AgendamentoService, useValue: { criarAgendamento } },
        { provide: ProfissionalService, useValue: { listarParaSelecao: () => of([
          { idPublico: 'admin', nome: 'Admin assistencial', tipoUsuario: 'ADMINISTRADOR' },
          { idPublico: 'prof', nome: 'Profissional assistencial', tipoUsuario: 'PROFISSIONAL' },
        ]) } },
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(NovoAgendamento);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('oferece ADMINISTRADOR e PROFISSIONAL retornados pela seleção assistencial', () => {
    expect(component.profissionais.map(p => p.idPublico)).toEqual(['admin', 'prof']);
    const options = Array.from(fixture.nativeElement.querySelectorAll('select option')) as HTMLOptionElement[];
    expect(options.find(o => o.value === 'admin')?.textContent).toContain('Admin assistencial');
    expect(options.find(o => o.value === 'prof')?.textContent).toContain('Profissional assistencial');
  });

  it('mostra o erro junto ao horário e mantém a seleção para correção', async () => {
    criarAgendamento.mockReturnValueOnce(throwError(() => new HttpErrorResponse({
      status: 422, error: { code: 'VALIDATION_FAILED', errors: [{ fieldName: 'horaAtendimento', message: 'Horário inválido.' }] },
    })));
    component.pacienteSelecionado = { idPublico: 'paciente-sintetico', nome: 'Paciente de teste' } as PacienteSelecao;
    component.profissionalSelecionadoId = 'prof';
    component.dataSelecionada = '2030-05-20';
    component.turnoSelecionado = 'MANHA';
    component.horaAtendimento = '08:00';

    component.confirmarAgendamento();
    await fixture.whenStable();

    const secaoHorario = fixture.nativeElement.querySelector('.step-card:nth-child(3)');
    expect(secaoHorario.querySelector('[role="alert"]').textContent).toContain('Horário inválido.');
    expect(component.horaAtendimento).toBe('08:00');
    expect(criarAgendamento).toHaveBeenCalledOnce();
  });
});

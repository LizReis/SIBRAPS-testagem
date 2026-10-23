import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { PacienteService } from '../../services/paciente/paciente-service';
import { ProfissionalService } from '../../services/profissional/profissional-service';
import { AgendamentoService } from '../../services/agendamento-service';

import { NovoAgendamento } from './novo-agendamento';

describe('NovoAgendamento', () => {
  let component: NovoAgendamento;
  let fixture: ComponentFixture<NovoAgendamento>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NovoAgendamento],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => null } } } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        { provide: PacienteService, useValue: {} },
        { provide: AgendamentoService, useValue: {} },
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
});

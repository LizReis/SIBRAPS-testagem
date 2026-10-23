import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';

import { PacienteService } from '../../services/paciente/paciente-service';
import { DetalhePaciente } from './detalhe-paciente';

describe('DetalhePaciente', () => {
  let component: DetalhePaciente;
  let fixture: ComponentFixture<DetalhePaciente>;
  const navigate = vi.fn();
  const paciente = {
    idPublico: 'id-publico-correto', nome: 'Paciente', nomeMae: 'Mãe', dataNascimento: '2000-01-01',
    sexo: 'FEMININO', racacor: 'PARDA', cns: '1', cpf: '1', telefone: '1', situacaoRua: false,
    tipoAcompanhamento: 'INDIVIDUAL', endereco: {}, usfReferencia: {}, capsReferencia: 'CAPS_I',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DetalhePaciente],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'id-publico-correto' } } } },
        { provide: Router, useValue: { navigate } },
        { provide: PacienteService, useValue: { buscarPorId: () => of(paciente) } },
      ],
    })
    .compileComponents();

    fixture = TestBed.createComponent(DetalhePaciente);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
  
  it('abre o histórico do paciente carregado pelo idPublico', () => {
    fixture.detectChanges();
    component.abrirHistoricoPaciente();
    expect(navigate).toHaveBeenCalledWith([
      '/pacientes/detalhes', 'id-publico-correto', 'historico',
    ]);
  });
});

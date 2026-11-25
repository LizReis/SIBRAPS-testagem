import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { PacienteDetalhe, PacienteService } from '../../services/paciente/paciente-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { DetalhePaciente } from './detalhe-paciente';

describe('DetalhePaciente', () => {
  let component: DetalhePaciente;
  let fixture: ComponentFixture<DetalhePaciente>;
  const detalhe: PacienteDetalhe = {
    paciente: {
      idPublico: 'id-publico-correto', nome: 'Paciente', nomeMae: 'Mãe', dataNascimento: '2000-01-01',
      sexo: 'FEMININO', racacor: 'PARDA', cns: '1', cpf: '1', telefone: '1', situacaoRua: false,
      tipoAcompanhamento: 'INDIVIDUAL', endereco: null, usfReferencia: null, capsReferencia: 'CAPS_I',
    },
    classificacaoRisco: null,
    atencaoNecessaria: '',
  };
  const buscarDetalhe = vi.fn(() => of(detalhe));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DetalhePaciente],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'id-publico-correto' } } } },
        { provide: PacienteService, useValue: { buscarDetalhe } },
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ tipoUsuario: 'RECEPCAO' }) } },
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
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
    component.abrirHistoricoPaciente();
    expect(buscarDetalhe).toHaveBeenCalledWith('id-publico-correto');
    expect(navigate).toHaveBeenCalledWith([
      '/pacientes/detalhes', 'id-publico-correto', 'historico',
    ]);
  });
});

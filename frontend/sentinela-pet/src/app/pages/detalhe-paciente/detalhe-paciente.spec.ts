import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { of, Subject } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

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
  const registrarBuscaAtiva = vi.fn(() => of(void 0));

  beforeEach(async () => {
    buscarDetalhe.mockClear();
    registrarBuscaAtiva.mockReset().mockReturnValue(of(void 0));
    await TestBed.configureTestingModule({
      imports: [DetalhePaciente],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'id-publico-correto' } } } },
        { provide: PacienteService, useValue: { buscarDetalhe, registrarBuscaAtiva } },
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
  
  it('não registra busca ativa por recepção mesmo com chamada direta', () => {
    component.detalhe = { ...detalhe, paciente: { ...detalhe.paciente, statusPaciente: 'ATIVO' }, classificacaoRisco: 'VERMELHO' };
    component.modal = 'busca'; component.descricao = 'Registro sintético';
    component.registrar();
    expect(registrarBuscaAtiva).not.toHaveBeenCalled();
  });
  it('bloqueia duplicidade, fechamento durante envio e reenvio de versão recusada', () => {
    component.detalhe = { ...detalhe, paciente: { ...detalhe.paciente, statusPaciente: 'ATIVO' }, classificacaoRisco: 'VERMELHO' };
    component.podeGerenciar = true; component.abrirBuscaAtiva(); component.descricao = 'Registro sintético';
    const resposta = new Subject<void>(); registrarBuscaAtiva.mockReturnValueOnce(resposta.asObservable());
    component.registrar(); component.registrar(); component.escape();
    expect(registrarBuscaAtiva).toHaveBeenCalledOnce(); expect(component.modal).toBe('busca');
    resposta.error(new HttpErrorResponse({status:422,error:{code:'STALE_VERSION'}}));
    component.registrar(); expect(registrarBuscaAtiva).toHaveBeenCalledOnce(); expect(component.vinculoDesatualizado).toBe(true);
  });
  it('limpa detalhe antigo durante nova leitura e após falha', () => {
    const resposta = new Subject<PacienteDetalhe>(); buscarDetalhe.mockReturnValueOnce(resposta.asObservable());
    component.carregar(); expect(component.carregando).toBe(true); expect(component.detalhe).toBeUndefined();
    resposta.error(new HttpErrorResponse({status:503}));
    expect(component.detalhe).toBeUndefined(); expect(component.carregando).toBe(false); expect(component.erro).toBeTruthy();
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
